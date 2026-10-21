import { APIRequestContext, APIResponse, expect, request, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';

/*
 * AC13 (specs/sharing-team-lists.md, T3a): parallel writes never return 500 and never leave broken data.
 * Scenarios: tag writes + moves in one list (incl. racing at the 200-tag limit); move A->B racing B->A;
 * task edit/move (PATCH, PUT with and without tags, moves in and out) racing a DELETE of its list;
 * creates racing at the 50-list limit; edits racing account deletion (self and admin); the old
 * PUT/PATCH/DELETE race on one task (a0aac6e).
 *
 * Talks to the API directly: E2E_API_URL (default: E2E_BASE_URL, default http://localhost:4200).
 * Rounds per scenario: E2E_STRESS_ROUNDS (default 10; the T3a QA run used 150).
 * Needs `docker compose up -d db` (data checks via `docker compose exec db mysql`).
 */

const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const API = process.env['E2E_API_URL'] ?? BASE;
const ROUNDS = Number(process.env['E2E_STRESS_ROUNDS'] ?? '10');
const PASSWORD = 'password123';
const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
let n = 0;
function uid() {
  n += 1;
  return `${RUN}-${n}`;
}

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: '..', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
  ).trim();
}
function num(query: string): number {
  return Number(sql(query));
}

interface Client {
  ctx: APIRequestContext;
  id: number;
  email: string;
  xsrf: string;
}
interface TaskList {
  id: number;
  name: string;
  personal: boolean;
}
interface Task {
  id: number;
  listId: number;
  title: string;
  tags: string[];
}

async function xsrfOf(ctx: APIRequestContext): Promise<string> {
  const state = await ctx.storageState();
  let c = state.cookies.find((x) => x.name === 'XSRF-TOKEN');
  if (!c) {
    await ctx.get('/api/auth/me');
    c = (await ctx.storageState()).cookies.find((x) => x.name === 'XSRF-TOKEN');
  }
  return c!.value;
}

async function newClient(tag: string): Promise<Client> {
  const ctx = await request.newContext({ baseURL: API });
  const email = `qa-t3a-stress-${tag}-${uid()}@example.com`;
  const r = await ctx.post('/api/auth/register', {
    data: { displayName: `Stress ${tag} ${uid()}`, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrfOf(ctx) },
  });
  expect(r.status(), await r.text()).toBe(201);
  const id = ((await r.json()) as { id: number }).id;
  return { ctx, id, email, xsrf: await xsrfOf(ctx) };
}

async function call(c: Client, method: string, path: string, data?: unknown): Promise<APIResponse> {
  return c.ctx.fetch(path, { method, data, headers: { 'X-XSRF-TOKEN': c.xsrf }, timeout: 60_000 });
}

async function ok<T>(p: Promise<APIResponse>, status = 200): Promise<T> {
  const r = await p;
  expect(r.status(), await r.text()).toBe(status);
  return (status === 204 ? null : await r.json()) as T;
}

const mkList = (c: Client, name: string) => ok<TaskList>(call(c, 'POST', '/api/lists', { name }), 201);
const mkTask = (c: Client, title: string, listId?: number) =>
  ok<Task>(call(c, 'POST', '/api/tasks', listId === undefined ? { title } : { title, listId }), 201);
const put = (c: Client, id: number, body: Record<string, unknown>) => call(c, 'PUT', `/api/tasks/${id}`, body);

/** Records every status; a 5xx or an unexpected status fails the scenario with details. */
class Tally {
  counts = new Map<string, number>();
  bad: string[] = [];
  constructor(private readonly label: string) {}
  async track(op: string, p: Promise<APIResponse>, allowed: number[]): Promise<APIResponse | null> {
    let r: APIResponse;
    try {
      r = await p;
    } catch (e) {
      this.bad.push(`${op}: request error ${(e as Error).message}`);
      return null;
    }
    const key = `${op} ${r.status()}`;
    this.counts.set(key, (this.counts.get(key) ?? 0) + 1);
    if (!allowed.includes(r.status())) this.bad.push(`${op}: ${r.status()} ${(await r.text()).slice(0, 300)}`);
    return r;
  }
  report() {
    const summary = [...this.counts.entries()].sort().map(([k, v]) => `${k} x${v}`);
    console.log(`[AC13 ${this.label}] ${summary.join(', ')}`);
    expect(this.bad, `${this.label}: unexpected statuses`).toEqual([]);
  }
}

/** Data checks for one user after a scenario. */
function checkData(ownerId: number, label: string) {
  const q = (s: string) => num(s);
  expect(q(`SELECT COUNT(*) FROM lists WHERE owner_id = ${ownerId} AND personal`), `${label}: one personal list`).toBeLessThanOrEqual(1);
  expect(q(`SELECT COUNT(*) FROM lists WHERE owner_id = ${ownerId}`), `${label}: <= 50 lists`).toBeLessThanOrEqual(50);
  expect(
    q(`SELECT COUNT(*) FROM tasks t JOIN lists l ON l.id = t.list_id WHERE l.owner_id = ${ownerId} AND t.owner_id <> l.owner_id`),
    `${label}: task owner = list owner`,
  ).toBe(0);
  expect(
    q(`SELECT COUNT(*) FROM tags g JOIN lists l ON l.id = g.list_id WHERE l.owner_id = ${ownerId} AND g.owner_id <> l.owner_id`),
    `${label}: tag owner = list owner`,
  ).toBe(0);
  expect(
    q(
      `SELECT COUNT(*) FROM task_tags tt JOIN tasks t ON t.id = tt.task_id JOIN tags g ON g.id = tt.tag_id WHERE (t.owner_id = ${ownerId} OR g.owner_id = ${ownerId}) AND t.list_id <> g.list_id`,
    ),
    `${label}: task_tags never cross lists`,
  ).toBe(0);
  expect(
    q(`SELECT COUNT(*) FROM tags g WHERE g.owner_id = ${ownerId} AND NOT EXISTS (SELECT 1 FROM task_tags tt WHERE tt.tag_id = g.id)`),
    `${label}: no unused (orphan) tags`,
  ).toBe(0);
  expect(
    q(`SELECT COALESCE(MAX(c), 0) FROM (SELECT COUNT(*) c FROM tags WHERE owner_id = ${ownerId} GROUP BY list_id) x`),
    `${label}: <= 200 tags per list`,
  ).toBeLessThanOrEqual(200);
  expect(
    q(`SELECT COUNT(*) FROM (SELECT task_id FROM task_tags tt JOIN tasks t ON t.id = tt.task_id WHERE t.owner_id = ${ownerId} GROUP BY task_id HAVING COUNT(*) > 10) x`),
    `${label}: <= 10 tags per task`,
  ).toBe(0);
}

test.describe.configure({ mode: 'parallel' });
test.describe('T3a AC13 concurrency stress', () => {
  test.setTimeout(30 * 60_000);

  test('tag writes and moves in the same list (and at the 200-tag limit)', async () => {
    const c = await newClient('tags');
    const a = await mkList(c, 'A');
    const b = await mkList(c, 'B');
    const tally = new Tally('tags+moves');
    for (let round = 0; round < ROUNDS; round += 1) {
      const k = round % 40; // keeps list A well under 200 tags (the limit has its own race below)
      const ts = await Promise.all(Array.from({ length: 6 }, (_, i) => mkTask(c, `r${round}-${i}`, a.id)));
      const ops: Promise<unknown>[] = [
        tally.track('PUT tags', put(c, ts[0].id, { title: 't0', tags: ['Common', `x${k}`, 'shared'] }), [200]),
        tally.track('PUT tags', put(c, ts[1].id, { title: 't1', tags: ['common', `X${k}`, 'Shared'] }), [200]),
        tally.track('PUT tags', put(c, ts[2].id, { title: 't2', tags: ['COMMON', `y${k}`] }), [200]),
        tally.track('PUT move A->B tags', put(c, ts[3].id, { title: 't3', listId: b.id, tags: ['Common', `x${k}`] }), [200]),
        tally.track('PUT move A->B', put(c, ts[4].id, { title: 't4', listId: b.id }), [200]),
        tally.track('DELETE task', call(c, 'DELETE', `/api/tasks/${ts[5].id}`), [204]),
        tally.track('PUT clear tags', put(c, ts[0].id, { title: 't0', tags: [] }), [200]),
        tally.track('PATCH', call(c, 'PATCH', `/api/tasks/${ts[2].id}`, { done: true }), [200]),
      ];
      await Promise.all(ops);
      // tidy so A stays small: move everything back / delete
      if (round % 5 === 4) {
        const page = (await ok<{ items: Task[] }>(call(c, 'GET', `/api/tasks?list=${b.id}`)));
        await Promise.all(page.items.map((t) => tally.track('PUT move B->A', put(c, t.id, { title: t.title, listId: a.id }), [200])));
      }
    }
    // spelling consistency: one tag per name (ignoring case/accents) per list
    expect(num(`SELECT COUNT(*) FROM (SELECT list_id, name FROM tags WHERE owner_id = ${c.id} GROUP BY list_id, name HAVING COUNT(*) > 1) x`)).toBe(0);
    tally.report();
    checkData(c.id, 'tags+moves');

    // racing at the 200-tag limit: target has 196 tags; 2 PUTs adding 3 new tags each + 2 moves bringing 3 new each
    const lim = new Tally('tag-limit');
    for (let round = 0; round < Math.max(3, Math.floor(ROUNDS / 3)); round += 1) {
      const full = await mkList(c, `Full ${round}`);
      for (let i = 0; i < 20; i += 1) {
        const t = await mkTask(c, `f${i}`, full.id);
        const tags = Array.from({ length: i < 19 ? 10 : 6 }, (_, k) => `F${i * 10 + k}`);
        await ok(put(c, t.id, { title: t.title, tags }));
      }
      expect(num(`SELECT COUNT(*) FROM tags WHERE list_id = ${full.id}`)).toBe(196);
      const src = await mkList(c, `Src ${round}`);
      const m1 = await mkTask(c, 'm1', src.id);
      await ok(put(c, m1.id, { title: 'm1', tags: ['n1', 'n2', 'n3'] }));
      const m2 = await mkTask(c, 'm2', src.id);
      await ok(put(c, m2.id, { title: 'm2', tags: ['n4', 'n5', 'n6'] }));
      const e1 = await mkTask(c, 'e1', full.id);
      const e2 = await mkTask(c, 'e2', full.id);
      const res = await Promise.all([
        lim.track('PUT +3 tags', put(c, e1.id, { title: 'e1', tags: ['p1', 'p2', 'p3'] }), [200, 400]),
        lim.track('PUT +3 tags', put(c, e2.id, { title: 'e2', tags: ['q1', 'q2', 'q3'] }), [200, 400]),
        lim.track('PUT move +3', put(c, m1.id, { title: 'm1', listId: full.id }), [200, 400]),
        lim.track('PUT move +3', put(c, m2.id, { title: 'm2', listId: full.id }), [200, 400]),
      ]);
      const okCount = res.filter((r) => r?.status() === 200).length;
      expect(okCount, 'only one of the four fits (196 + 3 = 199; any second would be 202)').toBe(1);
      for (const r of res) {
        if (r?.status() === 400) expect((await r.json()).errors).toEqual([{ field: 'tags', code: 'TAG_LIMIT', message: 'A list can have at most 200 tags' }]);
      }
      expect(num(`SELECT COUNT(*) FROM tags WHERE list_id = ${full.id}`)).toBe(199);
      await Promise.all([ok(call(c, 'DELETE', `/api/lists/${full.id}`), 204), ok(call(c, 'DELETE', `/api/lists/${src.id}`), 204)]);
    }
    lim.report();
    checkData(c.id, 'tag-limit');
    await c.ctx.dispose();
  });

  test('move A->B racing B->A (shared tags both ways)', async () => {
    const c = await newClient('swap');
    const a = await mkList(c, 'A');
    const b = await mkList(c, 'B');
    const xs = await Promise.all(Array.from({ length: 4 }, (_, i) => mkTask(c, `x${i}`, a.id)));
    const ys = await Promise.all(Array.from({ length: 4 }, (_, i) => mkTask(c, `y${i}`, b.id)));
    for (const t of [...xs, ...ys]) await ok(put(c, t.id, { title: t.title, tags: ['Both', `own${t.id}`] }));
    const tally = new Tally('swap');
    let inA = xs.map((t) => t.id);
    let inB = ys.map((t) => t.id);
    for (let round = 0; round < ROUNDS; round += 1) {
      await Promise.all([
        ...inA.map((id) => tally.track('PUT A->B', put(c, id, { title: `t${id}`, listId: b.id }), [200])),
        ...inB.map((id) => tally.track('PUT B->A', put(c, id, { title: `t${id}`, listId: a.id }), [200])),
        // tag edits in both lists at the same time
        tally.track('PUT tags', put(c, inA[0], { title: `t${inA[0]}`, tags: ['Both', 'extra'] }), [200]),
      ]);
      [inA, inB] = [inB, inA];
      // whatever happened, each task is in exactly one of the two lists
      const listA = num(`SELECT COUNT(*) FROM tasks WHERE list_id = ${a.id}`);
      const listB = num(`SELECT COUNT(*) FROM tasks WHERE list_id = ${b.id}`);
      expect(listA + listB).toBe(8);
    }
    tally.report();
    checkData(c.id, 'swap');
    await c.ctx.dispose();
  });

  test('task edit / move (PATCH, PUT with and without tags, in and out) racing a DELETE of its list', async () => {
    const c = await newClient('listdel');
    const keep = await mkList(c, 'Keep');
    const tally = new Tally('list-delete');
    for (let round = 0; round < ROUNDS; round += 1) {
      const doomed = await mkList(c, `Doomed ${round}`);
      const ts = await Promise.all(Array.from({ length: 6 }, (_, i) => mkTask(c, `d${round}-${i}`, doomed.id)));
      await ok(put(c, ts[1].id, { title: 'x', tags: ['a', 'b'] }));
      const incoming = await mkTask(c, `in${round}`, keep.id);
      await ok(put(c, incoming.id, { title: `in${round}`, tags: ['Keep', `k${round}`] }));
      const results = await Promise.all([
        tally.track('PATCH done', call(c, 'PATCH', `/api/tasks/${ts[0].id}`, { done: true }), [200, 404]),
        tally.track('PATCH status', call(c, 'PATCH', `/api/tasks/${ts[0].id}`, { status: 'IN_PROGRESS' }), [200, 404]),
        tally.track('PUT tags', put(c, ts[1].id, { title: 'x', tags: ['a', 'c', `r${round}`] }), [200, 404]),
        tally.track('PUT no tags', put(c, ts[2].id, { title: 'plain edit', priority: 'HIGH' }), [200, 404]),
        tally.track('PUT move out', put(c, ts[3].id, { title: 'out', listId: keep.id }), [200, 404]),
        tally.track('PUT move out+tags', put(c, ts[4].id, { title: 'out2', listId: keep.id, tags: ['Keep', 'n'] }), [200, 404]),
        tally.track('PUT move in', put(c, incoming.id, { title: 'in', listId: doomed.id }), [200, 404]),
        tally.track('POST into list', call(c, 'POST', '/api/tasks', { title: 'new', listId: doomed.id }), [201, 404]),
        tally.track('DELETE task', call(c, 'DELETE', `/api/tasks/${ts[5].id}`), [204, 404]),
        tally.track('PATCH rename list', call(c, 'PATCH', `/api/lists/${doomed.id}`, { name: 'renamed' }), [200, 404]),
        tally.track('GET list count', call(c, 'GET', `/api/lists/${doomed.id}`), [200, 404]),
        tally.track('DELETE list', call(c, 'DELETE', `/api/lists/${doomed.id}`), [204]),
      ]);
      // 404 codes are the right ones
      for (const r of results) {
        if (r && r.status() === 404) {
          const code = (await r.json()).code;
          expect(['NOT_FOUND', 'LIST_NOT_FOUND']).toContain(code);
        }
      }
      expect(num(`SELECT COUNT(*) FROM lists WHERE id = ${doomed.id}`)).toBe(0);
      expect(num(`SELECT COUNT(*) FROM tasks WHERE list_id = ${doomed.id}`)).toBe(0);
      expect(num(`SELECT COUNT(*) FROM tags WHERE list_id = ${doomed.id}`)).toBe(0);
      // the incoming task is either still in Keep (move lost) or gone with the list (move won)
      const where = sql(`SELECT COALESCE(MAX(list_id), 0) FROM tasks WHERE id = ${incoming.id}`);
      expect(['0', String(keep.id)]).toContain(where);
      // moved-out tasks that got 200 are in Keep
      const moveOut = results[4];
      if (moveOut?.status() === 200) expect(num(`SELECT list_id FROM tasks WHERE id = ${ts[3].id}`)).toBe(keep.id);
    }
    tally.report();
    checkData(c.id, 'list-delete');
    await c.ctx.dispose();
  });

  test('creates racing at the 50-list limit never exceed 50', async () => {
    const tally = new Tally('list-limit');
    for (let round = 0; round < Math.max(3, Math.floor(ROUNDS / 2)); round += 1) {
      const c = await newClient(`limit${round}`);
      const start = 46 + (round % 4); // 47..50 including "My tasks"
      for (let i = 1; i < start; i += 1) await mkList(c, `L${i}`);
      const res = await Promise.all(
        Array.from({ length: 8 }, (_, i) => tally.track('POST list', call(c, 'POST', '/api/lists', { name: `race ${i}` }), [201, 409])),
      );
      const created = res.filter((r) => r?.status() === 201).length;
      expect(created).toBe(50 - start);
      expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${c.id}`)).toBe(50);
      for (const r of res) if (r?.status() === 409) expect((await r.json()).code).toBe('LIST_LIMIT');
      // delete one and race again: exactly one more fits
      const ls = await ok<TaskList[]>(call(c, 'GET', '/api/lists'));
      const victim = ls.find((l) => !l.personal)!;
      const res2 = await Promise.all([
        tally.track('DELETE list', call(c, 'DELETE', `/api/lists/${victim.id}`), [204]),
        ...Array.from({ length: 4 }, (_, i) => tally.track('POST list', call(c, 'POST', '/api/lists', { name: `again ${i}` }), [201, 409])),
      ]);
      expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${c.id}`)).toBeLessThanOrEqual(50);
      expect(res2.filter((r) => r?.status() === 201).length).toBeLessThanOrEqual(1);
      checkData(c.id, 'list-limit');
      await c.ctx.dispose();
    }
    tally.report();
  });

  test('edits racing account deletion (self and admin) leave nothing behind', async () => {
    const tally = new Tally('account-delete');
    const admin = await newClient('admin');
    sql(`UPDATE users SET role = 'ADMIN' WHERE id = ${admin.id}`);
    await admin.ctx.dispose();
    const actx = await request.newContext({ baseURL: API });
    const lr = await actx.post('/api/auth/login', { data: { email: admin.email, password: PASSWORD }, headers: { 'X-XSRF-TOKEN': await xsrfOf(actx) } });
    expect(lr.status()).toBe(200);
    const adminC: Client = { ctx: actx, id: admin.id, email: admin.email, xsrf: await xsrfOf(actx) };

    for (let round = 0; round < Math.max(4, Math.floor(ROUNDS / 2)); round += 1) {
      const c = await newClient(`acct${round}`);
      const a = await mkList(c, 'A');
      const b = await mkList(c, 'B');
      const ts = await Promise.all(Array.from({ length: 5 }, (_, i) => mkTask(c, `t${i}`, i % 2 ? a.id : b.id)));
      await ok(put(c, ts[0].id, { title: 't0', tags: ['x', 'y'] }));
      const any = [200, 201, 204, 401, 404];
      const deleter =
        round % 2 === 0
          ? tally.track('POST account/delete', call(c, 'POST', '/api/account/delete', { password: PASSWORD }), [204])
          : tally.track('admin DELETE user', call(adminC, 'DELETE', `/api/admin/users/${c.id}`), [204]);
      await Promise.all([
        tally.track('PUT tags', put(c, ts[0].id, { title: 't0', tags: ['x', 'z'] }), any),
        tally.track('PUT move', put(c, ts[1].id, { title: 't1', listId: b.id, tags: ['m'] }), any),
        tally.track('PUT move', put(c, ts[2].id, { title: 't2', listId: a.id }), any),
        tally.track('PATCH', call(c, 'PATCH', `/api/tasks/${ts[3].id}`, { done: true }), any),
        tally.track('POST task', call(c, 'POST', '/api/tasks', { title: 'n', listId: a.id }), any),
        tally.track('POST list', call(c, 'POST', '/api/lists', { name: 'late' }), any),
        tally.track('PATCH list', call(c, 'PATCH', `/api/lists/${a.id}`, { name: 'late rename' }), any),
        tally.track('DELETE list', call(c, 'DELETE', `/api/lists/${b.id}`), any),
        deleter,
      ]);
      for (const t of ['users WHERE id', 'lists WHERE owner_id', 'tasks WHERE owner_id', 'tags WHERE owner_id']) {
        expect(num(`SELECT COUNT(*) FROM ${t} = ${c.id}`), `${t} after delete`).toBe(0);
      }
      await c.ctx.dispose();
    }
    tally.report();
    await actx.dispose();
  });

  test('old race (a0aac6e): PUT/PATCH racing DELETE on the same task, in a non-personal list', async () => {
    const c = await newClient('old');
    const l = await mkList(c, 'L');
    const tally = new Tally('old-race');
    for (let round = 0; round < ROUNDS * 2; round += 1) {
      const t = await mkTask(c, `o${round}`, round % 2 ? l.id : undefined);
      await Promise.all([
        tally.track('PUT', put(c, t.id, { title: 'e', tags: ['a'] }), [200, 404]),
        tally.track('PUT no tags', put(c, t.id, { title: 'e2' }), [200, 404]),
        tally.track('PATCH', call(c, 'PATCH', `/api/tasks/${t.id}`, { done: true }), [200, 404]),
        tally.track('DELETE', call(c, 'DELETE', `/api/tasks/${t.id}`), [204, 404]),
        tally.track('DELETE', call(c, 'DELETE', `/api/tasks/${t.id}`), [204, 404]),
      ]);
      // T5a: DELETE moves the task to the trash. No racing PUT/PATCH may bring it back or leave it live.
      expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${t.id} AND deleted_at IS NULL`)).toBe(0);
      expect(num(`SELECT COUNT(*) FROM tasks WHERE id = ${t.id} AND deleted_at IS NOT NULL`)).toBe(1);
    }
    tally.report();
    checkData(c.id, 'old-race');
    await c.ctx.dispose();
  });
});
