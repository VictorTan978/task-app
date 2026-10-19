import { APIRequestContext, APIResponse, expect, request, test } from '@playwright/test';
import { execFile, execFileSync } from 'node:child_process';
import { promisify } from 'node:util';

/*
 * AC33 (specs/sharing-team-lists.md, T3b): parallel load never returns 500 and limits never go over.
 * Scenarios: tag writes from two members of one list (incl. at the 200-tag limit); accept racing cancel / decline /
 * expiry; accepts racing at the 20-member limit; invites racing at the 20-pending limit and against accepts; removing
 * a member while they write; deleting a list while members write; a member writing while the owner's account is
 * deleted (self and admin); a member's own account deleted while they write; renames racing all of the above.
 * T3a AC13 races are in lists-stress.spec.ts.
 *
 * API: E2E_API_URL (default E2E_BASE_URL). Rounds: E2E_STRESS_ROUNDS (default 10; the T3b QA run used 150).
 * Needs `docker compose up -d db`. Every RATE_LIMIT_* (incl. invites) must be high.
 */

const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const API = process.env['E2E_API_URL'] ?? BASE;
const ROUNDS = Number(process.env['E2E_STRESS_ROUNDS'] ?? '10');
const PASSWORD = 'password123';
const RUN = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const execFileP = promisify(execFile);
let n = 0;
function uid() {
  n += 1;
  return `${RUN}-${n}`;
}
const MYSQL = ['compose', 'exec', '-T', 'db', 'mysql', '--default-character-set=utf8mb4', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e'];
function sql(query: string): string {
  return execFileSync('docker', [...MYSQL, query], { cwd: '..', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
}
async function sqlAsync(query: string): Promise<void> {
  await execFileP('docker', [...MYSQL, query], { cwd: '..' });
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
  memberCount: number;
}
interface Task {
  id: number;
  title: string;
}

async function xsrfOf(ctx: APIRequestContext): Promise<string> {
  let c = (await ctx.storageState()).cookies.find((x) => x.name === 'XSRF-TOKEN');
  if (!c) {
    await ctx.get('/api/auth/me');
    c = (await ctx.storageState()).cookies.find((x) => x.name === 'XSRF-TOKEN');
  }
  return c!.value;
}
async function newClient(tag: string, verified = true): Promise<Client> {
  const ctx = await request.newContext({ baseURL: API });
  const email = `qa-t3b-stress-${tag}-${uid()}@example.com`;
  const r = await ctx.post('/api/auth/register', {
    data: { displayName: `S3b ${tag} ${uid()}`, email, password: PASSWORD },
    headers: { 'X-XSRF-TOKEN': await xsrfOf(ctx) },
  });
  expect(r.status(), await r.text()).toBe(201);
  const id = ((await r.json()) as { id: number }).id;
  if (verified) sql(`UPDATE users SET email_verified_at = NOW(6) WHERE id = ${id}`);
  return { ctx, id, email, xsrf: await xsrfOf(ctx) };
}
async function adminClient(): Promise<Client> {
  const a = await newClient('admin');
  sql(`UPDATE users SET role = 'ADMIN' WHERE id = ${a.id}`);
  await a.ctx.dispose();
  const ctx = await request.newContext({ baseURL: API });
  const r = await ctx.post('/api/auth/login', { data: { email: a.email, password: PASSWORD }, headers: { 'X-XSRF-TOKEN': await xsrfOf(ctx) } });
  expect(r.status()).toBe(200);
  return { ctx, id: a.id, email: a.email, xsrf: await xsrfOf(ctx) };
}
function call(c: Client, method: string, path: string, data?: unknown): Promise<APIResponse> {
  return c.ctx.fetch(path, { method, data, headers: { 'X-XSRF-TOKEN': c.xsrf }, timeout: 60_000 });
}
async function ok<T>(p: Promise<APIResponse>, status = 200): Promise<T> {
  const r = await p;
  expect(r.status(), `${r.url()} ${await r.text()}`).toBe(status);
  return (status === 204 ? null : await r.json()) as T;
}
const mkList = (c: Client, name: string) => ok<TaskList>(call(c, 'POST', '/api/lists', { name }), 201);
const mkTask = (c: Client, title: string, listId: number) => ok<Task>(call(c, 'POST', '/api/tasks', { title, listId }), 201);
const put = (c: Client, id: number, body: Record<string, unknown>) => call(c, 'PUT', `/api/tasks/${id}`, body);
const inviteP = (o: Client, listId: number, email: string) => call(o, 'POST', `/api/lists/${listId}/invitations`, { email });
async function invId(listId: number, email: string): Promise<number> {
  return num(`SELECT id FROM list_invitations WHERE list_id = ${listId} AND email = '${email.toLowerCase()}'`);
}
async function join(owner: Client, listId: number, m: Client) {
  await ok(inviteP(owner, listId, m.email), 201);
  await ok(call(m, 'POST', `/api/invitations/${await invId(listId, m.email)}/accept`));
}

/** Runs f after a random delay of 0..maxMs, so both sides of a race get to win sometimes. */
function jitter<T>(maxMs: number, f: () => Promise<T>): Promise<T> {
  return new Promise<T>((r, j) => setTimeout(() => f().then(r, j), Math.floor(Math.random() * maxMs)));
}

class Tally {
  counts = new Map<string, number>();
  bad: string[] = [];
  constructor(private readonly label: string) {}
  async track(op: string, p: Promise<APIResponse>, allowed: number[], codes?: string[]): Promise<APIResponse | null> {
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
    else if (codes && r.status() >= 400) {
      const code = ((await r.json().catch(() => ({}))) as { code?: string }).code ?? '';
      if (!codes.includes(code)) this.bad.push(`${op}: ${r.status()} unexpected code ${code}`);
    }
    return r;
  }
  report() {
    console.log(`[AC33 ${this.label}] ${[...this.counts.entries()].sort().map(([k, v]) => `${k} x${v}`).join(', ')}`);
    expect(this.bad, `${this.label}: unexpected statuses`).toEqual([]);
  }
}

/** Database-wide sharing invariants (cheap enough to run after every scenario). */
function checkSharingData(label: string) {
  const checks: [string, string][] = [
    ['every list has its owner as a member', `SELECT COUNT(*) FROM lists l WHERE NOT EXISTS (SELECT 1 FROM list_members m WHERE m.list_id = l.id AND m.user_id = l.owner_id)`],
    ['<= 20 members per list', `SELECT COUNT(*) FROM (SELECT list_id FROM list_members GROUP BY list_id HAVING COUNT(*) > 20) x`],
    ['<= 20 pending invitations per list', `SELECT COUNT(*) FROM (SELECT list_id FROM list_invitations WHERE expires_at > NOW(6) GROUP BY list_id HAVING COUNT(*) > 20) x`],
    ['personal lists have one member', `SELECT COUNT(*) FROM lists l WHERE l.personal AND (SELECT COUNT(*) FROM list_members m WHERE m.list_id = l.id) <> 1`],
    ['no invitations on personal lists', `SELECT COUNT(*) FROM list_invitations i JOIN lists l ON l.id = i.list_id WHERE l.personal`],
    ['tag owner = list owner', `SELECT COUNT(*) FROM tags g JOIN lists l ON l.id = g.list_id WHERE g.owner_id <> l.owner_id`],
    ['task_tags never cross lists', `SELECT COUNT(*) FROM task_tags tt JOIN tasks t ON t.id = tt.task_id JOIN tags g ON g.id = tt.tag_id WHERE t.list_id <> g.list_id`],
    ['<= 200 tags per list', `SELECT COUNT(*) FROM (SELECT list_id FROM tags GROUP BY list_id HAVING COUNT(*) > 200) x`],
    ['<= 50 owned lists', `SELECT COUNT(*) FROM (SELECT owner_id FROM lists GROUP BY owner_id HAVING COUNT(*) > 50) x`],
    ['one personal list per user', `SELECT COUNT(*) FROM users u WHERE (SELECT COUNT(*) FROM lists l WHERE l.owner_id = u.id AND l.personal) <> 1`],
    ['stress: no unused tags on stress lists', `SELECT COUNT(*) FROM tags g JOIN users u ON u.id = g.owner_id WHERE u.email LIKE 'qa-t3b-stress-%' AND NOT EXISTS (SELECT 1 FROM task_tags tt WHERE tt.tag_id = g.id)`],
  ];
  for (const [name, q] of checks) expect(num(q), `${label}: ${name}`).toBe(0);
}

test.describe.configure({ mode: 'parallel' });
test.describe('T3b AC33 sharing concurrency stress', () => {
  test.setTimeout(30 * 60_000);

  test('tag writes from two members of the same list (and racing at the 200-tag limit)', async () => {
    const o = await newClient('tago');
    const m = await newClient('tagm');
    const l = await mkList(o, 'Tags');
    const other = await mkList(m, 'Member list');
    await join(o, l.id, m);
    const tally = new Tally('member tags');
    for (let round = 0; round < ROUNDS; round += 1) {
      const k = round % 40;
      const [a, b, c] = await Promise.all([mkTask(o, `a${round}`, l.id), mkTask(m, `b${round}`, l.id), mkTask(m, `c${round}`, l.id)]);
      await Promise.all([
        tally.track('owner PUT tags', put(o, a.id, { title: 'a', tags: ['Common', `x${k}`, 'café'] }), [200, 404]),
        tally.track('member PUT tags', put(m, b.id, { title: 'b', tags: ['common', `X${k}`, 'CAFE'] }), [200]),
        tally.track('member move out', put(m, c.id, { title: 'c', listId: other.id, tags: ['Common', `y${k}`] }), [200]),
        tally.track('owner PATCH', call(o, 'PATCH', `/api/tasks/${b.id}`, { done: true }), [200]),
        tally.track('member DELETE', call(m, 'DELETE', `/api/tasks/${a.id}`), [204, 404]),
        tally.track('owner PUT clear', put(o, a.id, { title: 'a', tags: [] }), [200, 404]),
        tally.track('owner rename', call(o, 'PATCH', `/api/lists/${l.id}`, { name: `Tags ${round}` }), [200]),
      ]);
      if (round % 5 === 4) {
        const page = await ok<{ items: Task[] }>(call(m, 'GET', `/api/tasks?list=${other.id}`));
        await Promise.all(page.items.map((t) => tally.track('member move back', put(m, t.id, { title: t.title, listId: l.id }), [200])));
      }
    }
    expect(num(`SELECT COUNT(*) FROM (SELECT list_id, name FROM tags WHERE list_id IN (${l.id}, ${other.id}) GROUP BY list_id, name HAVING COUNT(*) > 1) x`)).toBe(0);
    tally.report();

    // two members racing at the 200-tag limit: 196 tags, four writers adding 3 new each -> exactly one wins
    const lim = new Tally('member tag limit');
    for (let round = 0; round < Math.max(3, Math.floor(ROUNDS / 3)); round += 1) {
      const full = await mkList(o, `Full ${round}`);
      await join(o, full.id, m);
      for (let i = 0; i < 20; i += 1) {
        const t = await mkTask(o, `f${i}`, full.id);
        await ok(put(o, t.id, { title: t.title, tags: Array.from({ length: i < 19 ? 10 : 6 }, (_, j) => `F${i * 10 + j}`) }));
      }
      const [e1, e2] = await Promise.all([mkTask(o, 'e1', full.id), mkTask(m, 'e2', full.id)]);
      const src = await mkList(m, `Src ${round}`);
      const mv = await mkTask(m, 'mv', src.id);
      await ok(put(m, mv.id, { title: 'mv', tags: ['n1', 'n2', 'n3'] }));
      const res = await Promise.all([
        lim.track('owner +3', put(o, e1.id, { title: 'e1', tags: ['p1', 'p2', 'p3'] }), [200, 400]),
        lim.track('member +3', put(m, e2.id, { title: 'e2', tags: ['q1', 'q2', 'q3'] }), [200, 400]),
        lim.track('member move +3', put(m, mv.id, { title: 'mv', listId: full.id }), [200, 400]),
      ]);
      expect(res.filter((r) => r?.status() === 200).length).toBe(1);
      expect(num(`SELECT COUNT(*) FROM tags WHERE list_id = ${full.id}`)).toBe(199);
      await ok(call(o, 'DELETE', `/api/lists/${full.id}`), 204);
      await ok(call(m, 'DELETE', `/api/lists/${src.id}`), 204);
    }
    lim.report();
    checkSharingData('member tags');
    await Promise.all([o.ctx.dispose(), m.ctx.dispose()]);
  });

  test('accept racing cancel, decline and expiry; invites racing at the 20-pending limit', async () => {
    const o = await newClient('invo');
    const l = await mkList(o, 'Invites');
    const tally = new Tally('accept vs cancel/expiry');
    const invitees = await Promise.all(Array.from({ length: 3 }, (_, i) => newClient(`inv${i}`)));
    for (let round = 0; round < ROUNDS; round += 1) {
      const [a, b, c] = invitees;
      // make sure they're not members, then invite all three
      for (const u of invitees) {
        await call(u, 'POST', `/api/lists/${l.id}/leave`);
        await ok(inviteP(o, l.id, u.email), 201);
      }
      const ia = await invId(l.id, a.email);
      const ib = await invId(l.id, b.email);
      const ic = await invId(l.id, c.email);
      const [acc, can, acc2, dec, acc3] = await Promise.all([
        tally.track('accept (vs cancel)', jitter(30, () => call(a, 'POST', `/api/invitations/${ia}/accept`)), [200, 404], ['INVITATION_NOT_FOUND']),
        tally.track('cancel', jitter(30, () => call(o, 'DELETE', `/api/lists/${l.id}/invitations/${ia}`)), [204, 404], ['INVITATION_NOT_FOUND']),
        tally.track('accept (vs decline)', jitter(30, () => call(b, 'POST', `/api/invitations/${ib}/accept`)), [200, 404], ['INVITATION_NOT_FOUND']),
        tally.track('decline', call(b, 'POST', `/api/invitations/${ib}/decline`), [204, 404], ['INVITATION_NOT_FOUND']),
        tally.track('accept (vs expiry)', jitter(600, () => call(c, 'POST', `/api/invitations/${ic}/accept`)), [200, 404], ['INVITATION_NOT_FOUND']),
        sqlAsync(`UPDATE list_invitations SET invited_at = NOW(6) - INTERVAL 15 DAY, expires_at = NOW(6) - INTERVAL 1 DAY WHERE id = ${ic}`).then(() => null),
      ]);
      // exactly one of accept/cancel won; membership matches
      const aMember = num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${l.id} AND user_id = ${a.id}`);
      expect([acc?.status(), can?.status()].filter((s) => s === 200 || s === 204).length, 'accept xor cancel').toBe(1);
      expect(aMember).toBe(acc?.status() === 200 ? 1 : 0);
      expect([acc2?.status(), dec?.status()].filter((s) => s === 200 || s === 204).length, 'accept xor decline').toBe(1);
      expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${l.id} AND user_id = ${c.id}`)).toBe(acc3?.status() === 200 ? 1 : 0);
      expect(num(`SELECT COUNT(*) FROM list_invitations WHERE id IN (${ia}, ${ib})`)).toBe(0);
      await sqlAsync(`DELETE FROM list_invitations WHERE id = ${ic}`);
    }
    tally.report();

    // 30 parallel invites into an empty list -> never more than 20 pending
    const pend = new Tally('pending limit');
    for (let round = 0; round < Math.max(3, Math.floor(ROUNDS / 5)); round += 1) {
      const p = await mkList(o, `Pending ${round}`);
      const res = await Promise.all(
        Array.from({ length: 30 }, (_, i) =>
          pend.track('invite', inviteP(o, p.id, `qa-t3b-stress-p-${RUN}-${round}-${i}@example.com`), [201, 409], ['INVITATION_LIMIT']),
        ),
      );
      expect(res.filter((r) => r?.status() === 201).length).toBe(20);
      expect(num(`SELECT COUNT(*) FROM list_invitations WHERE list_id = ${p.id}`)).toBe(20);
      // the same address invited 5x at once -> one row
      const dupList = await mkList(o, `Dup ${round}`);
      const dup = await Promise.all(Array.from({ length: 5 }, () => pend.track('dup invite', inviteP(o, dupList.id, `qa-t3b-stress-dup-${RUN}-${round}@example.com`), [201, 409], ['ALREADY_INVITED'])));
      expect(dup.filter((r) => r?.status() === 201).length).toBe(1);
      await ok(call(o, 'DELETE', `/api/lists/${p.id}`), 204);
      await ok(call(o, 'DELETE', `/api/lists/${dupList.id}`), 204);
    }
    pend.report();
    checkSharingData('invites');
    await Promise.all([o.ctx.dispose(), ...invitees.map((u) => u.ctx.dispose())]);
  });

  test('several accepts racing at the 20-member limit (plus invites racing them) never exceed 20', async () => {
    const tally = new Tally('member limit');
    const o = await newClient('limo');
    const pool = await Promise.all(Array.from({ length: 22 }, (_, i) => newClient(`lim${i}`)));
    for (let round = 0; round < Math.max(3, Math.floor(ROUNDS / 5)); round += 1) {
      const l = await mkList(o, `Limit ${round}`);
      const start = 15 + (round % 4); // 16..19 members incl. owner
      for (let i = 0; i < start - 1; i += 1) await join(o, l.id, pool[i]);
      const racers = pool.slice(start - 1, start + 3); // 4 accepts
      for (const r of racers) await ok(inviteP(o, l.id, r.email), 201);
      const ids = await Promise.all(racers.map((r) => invId(l.id, r.email)));
      const res = await Promise.all([
        ...racers.map((r, i) => tally.track('accept', call(r, 'POST', `/api/invitations/${ids[i]}/accept`), [200, 409], ['MEMBER_LIMIT'])),
        tally.track('invite while accepting', inviteP(o, l.id, pool[21].email), [201, 409], ['MEMBER_LIMIT', 'ALREADY_INVITED', 'ALREADY_MEMBER']),
        tally.track('rename while accepting', call(o, 'PATCH', `/api/lists/${l.id}`, { name: `Limit ${round} r` }), [200]),
      ]);
      const accepted = res.slice(0, 4).filter((r) => r?.status() === 200).length;
      expect(accepted).toBe(Math.min(4, 20 - start));
      expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${l.id}`)).toBe(start + accepted);
      expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${l.id}`)).toBeLessThanOrEqual(20);
      // refused invitations are kept
      expect(num(`SELECT COUNT(*) FROM list_invitations WHERE list_id = ${l.id} AND id IN (${ids.join(',')})`)).toBe(4 - accepted);
      await ok(call(o, 'DELETE', `/api/lists/${l.id}`), 204);
    }
    tally.report();
    checkSharingData('member limit');
    await Promise.all([o.ctx.dispose(), ...pool.map((u) => u.ctx.dispose())]);
  });

  test('removing a member / the member leaving while they write; deleting a list while members write', async () => {
    const o = await newClient('remo');
    const m = await newClient('remm');
    const m2 = await newClient('remm2');
    const mOwn = await mkList(m, 'M own');
    const tally = new Tally('remove/leave/delete');
    const W = [200, 201, 204, 404];
    for (let round = 0; round < ROUNDS; round += 1) {
      const l = await mkList(o, `Shared ${round}`);
      await join(o, l.id, m);
      await join(o, l.id, m2);
      const ts = await Promise.all(Array.from({ length: 5 }, (_, i) => mkTask(m, `r${round}-${i}`, l.id)));
      const incoming = await mkTask(m, `in${round}`, mOwn.id);
      const mode = round % 3; // 0 remove, 1 leave, 2 delete list
      const ops = [
        tally.track('member PUT tags', put(m, ts[0].id, { title: 'x', tags: ['a', `r${round % 20}`] }), W),
        tally.track('member PUT', put(m, ts[1].id, { title: 'y', priority: 'HIGH' }), W),
        tally.track('member PATCH', call(m, 'PATCH', `/api/tasks/${ts[2].id}`, { done: true }), W),
        tally.track('member move out', put(m, ts[3].id, { title: 'out', listId: mOwn.id, tags: ['b'] }), W),
        tally.track('member move in', put(m, incoming.id, { title: 'in', listId: l.id }), W),
        tally.track('member POST', call(m, 'POST', '/api/tasks', { title: 'new', listId: l.id }), W),
        tally.track('member DELETE task', call(m, 'DELETE', `/api/tasks/${ts[4].id}`), W),
        tally.track('member GET members', call(m, 'GET', `/api/lists/${l.id}/members`), [200, 404]),
        tally.track('member2 PUT tags', put(m2, ts[0].id, { title: 'z', tags: ['a', 'c'] }), W),
        tally.track('owner rename', call(o, 'PATCH', `/api/lists/${l.id}`, { name: `S${round}` }), mode === 2 ? [200, 404] : [200]),
        mode === 0
          ? tally.track('owner remove member', call(o, 'DELETE', `/api/lists/${l.id}/members/${m.id}`), [204])
          : mode === 1
            ? tally.track('member leave', call(m, 'POST', `/api/lists/${l.id}/leave`), [204])
            : tally.track('owner delete list', call(o, 'DELETE', `/api/lists/${l.id}`), [204]),
      ];
      const res = await Promise.all(ops);
      for (const r of res) {
        if (r?.status() === 404) expect(['NOT_FOUND', 'LIST_NOT_FOUND']).toContain((await r.json()).code);
      }
      expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${l.id} AND user_id = ${m.id}`)).toBe(0);
      // after removal the member can't touch the list
      if (mode !== 2) {
        expect((await call(m, 'GET', `/api/tasks?list=${l.id}`)).status()).toBe(404);
        // tasks they added stay (unless deleted/moved by the ops above)
        expect(num(`SELECT COUNT(*) FROM tasks WHERE id IN (${ts[0].id}, ${ts[1].id}, ${ts[2].id}) AND list_id = ${l.id}`)).toBe(3);
      } else {
        expect(num(`SELECT COUNT(*) FROM tasks WHERE list_id = ${l.id}`)).toBe(0);
      }
      // the incoming task is either in M own or in the shared list
      expect(['0', String(mOwn.id), String(l.id)]).toContain(sql(`SELECT COALESCE(MAX(list_id), 0) FROM tasks WHERE id = ${incoming.id}`));
      if (mode !== 2) await ok(call(o, 'DELETE', `/api/lists/${l.id}`), 204);
    }
    tally.report();
    checkSharingData('remove/leave/delete');
    await Promise.all([o.ctx.dispose(), m.ctx.dispose(), m2.ctx.dispose()]);
  });

  test("members writing while the owner's account is deleted (self and admin); a member's own account deleted while writing", async () => {
    const tally = new Tally('account deletion');
    const admin = await adminClient();
    const W = [200, 201, 204, 401, 404];
    for (let round = 0; round < Math.max(4, Math.floor(ROUNDS / 2)); round += 1) {
      const o = await newClient(`acco${round}`);
      const m = await newClient(`accm${round}`);
      const keeper = await newClient(`acck${round}`); // owns a list m is in
      const l = await mkList(o, 'Doomed with owner');
      await join(o, l.id, m);
      const k = await mkList(keeper, 'Survives');
      await join(keeper, k.id, m);
      const mine = await mkList(m, 'M own');
      const ts = await Promise.all(Array.from({ length: 3 }, (_, i) => mkTask(m, `t${i}`, l.id)));
      const kt = await Promise.all(Array.from({ length: 3 }, (_, i) => mkTask(m, `k${i}`, k.id)));
      await ok(put(m, kt[0].id, { title: 'k0', tags: ['KeepTag'] }));
      // pending invitation to m's address that must disappear with the account
      const pendList = await mkList(keeper, 'Pending for m');
      await ok(inviteP(keeper, pendList.id, m.email), 201);
      const later = <T,>(f: () => Promise<T>) => new Promise<T>((r) => setTimeout(() => r(f()), Math.floor(Math.random() * 40)));
      const ownerDel =
        round % 2 === 0
          ? tally.track('owner self delete', later(() => call(o, 'POST', '/api/account/delete', { password: PASSWORD })), [204])
          : tally.track('admin delete owner', later(() => call(admin, 'DELETE', `/api/admin/users/${o.id}`)), [204]);
      const memberDel =
        round % 2 === 0
          ? tally.track('admin delete member', later(() => call(admin, 'DELETE', `/api/admin/users/${m.id}`)), [204])
          : tally.track('member self delete', later(() => call(m, 'POST', '/api/account/delete', { password: PASSWORD })), [204]);
      await Promise.all([
        tally.track('member PUT tags (owner list)', put(m, ts[0].id, { title: 'x', tags: ['a'] }), W),
        tally.track('member PATCH (owner list)', call(m, 'PATCH', `/api/tasks/${ts[1].id}`, { done: true }), W),
        tally.track('member move to own', put(m, ts[2].id, { title: 'mv', listId: mine.id }), W),
        tally.track('member POST (owner list)', call(m, 'POST', '/api/tasks', { title: 'n', listId: l.id }), W),
        tally.track('owner rename', call(o, 'PATCH', `/api/lists/${l.id}`, { name: 'late' }), W),
        tally.track('owner invite', inviteP(o, l.id, `qa-t3b-stress-late-${uid()}@example.com`), [201, 401, 404]),
        tally.track('member PUT tags (keeper list)', put(m, kt[1].id, { title: 'k1', tags: ['KeepTag', 'k'] }), W),
        tally.track('member POST (keeper list)', call(m, 'POST', '/api/tasks', { title: 'kn', listId: k.id }), W),
        tally.track('keeper rename', call(keeper, 'PATCH', `/api/lists/${k.id}`, { name: 'Survives r' }), [200]),
        tally.track('keeper PUT tags', put(keeper, kt[2].id, { title: 'k2', tags: ['KeepTag'] }), [200]),
        ownerDel,
        memberDel,
      ]);
      for (const id of [o.id, m.id]) {
        expect(num(`SELECT COUNT(*) FROM users WHERE id = ${id}`)).toBe(0);
        expect(num(`SELECT COUNT(*) FROM lists WHERE owner_id = ${id}`)).toBe(0);
        expect(num(`SELECT COUNT(*) FROM list_members WHERE user_id = ${id}`)).toBe(0);
      }
      expect(num(`SELECT COUNT(*) FROM lists WHERE id = ${l.id}`)).toBe(0);
      expect(num(`SELECT COUNT(*) FROM tasks WHERE list_id = ${l.id}`)).toBe(0);
      expect(num(`SELECT COUNT(*) FROM list_invitations WHERE email = '${m.email}'`)).toBe(0);
      // keeper's list survives with m's tasks (addedBy null) and its tags
      expect(num(`SELECT COUNT(*) FROM tasks WHERE id IN (${kt.map((t) => t.id).join(',')}) AND list_id = ${k.id} AND owner_id IS NULL`)).toBe(3);
      expect(num(`SELECT COUNT(*) FROM tags WHERE list_id = ${k.id} AND name = 'KeepTag'`)).toBe(1);
      expect(num(`SELECT COUNT(*) FROM list_members WHERE list_id = ${k.id}`)).toBe(1);
      await Promise.all([o.ctx.dispose(), m.ctx.dispose(), keeper.ctx.dispose()]);
    }
    tally.report();
    checkSharingData('account deletion');
    await admin.ctx.dispose();
  });
});
