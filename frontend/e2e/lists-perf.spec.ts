import { APIRequestContext, expect, request, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

/*
 * AC16 (specs/sharing-team-lists.md, T3a): with ~10,000 tasks in one list, every sort / search / tab (and tag)
 * returns the first page in under 300 ms on the dev machine. Same method as T2 (tasks-search-sort-pagination AC16):
 * 5 sorts x 4 tabs x 4 searches x 2 tag filters = 160 combinations, median of 3 timed calls each + page 2.
 * The same user also has 10,000 tasks in "My tasks", so the list filter must use the list_id indexes.
 *
 * Gated: E2E_PERF=1 (seeds 20,000 rows with SQL, cleans up after). API: E2E_API_URL (default E2E_BASE_URL).
 * Writes timings to ../test-results/lists/ac16-timings.txt.
 */

const RUN_PERF = process.env['E2E_PERF'] === '1';
const BASE = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
const API = process.env['E2E_API_URL'] ?? BASE;
const OUT = '../test-results/lists';

function sql(query: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'mysql', '--default-character-set=utf8mb4', '-utaskapp', '-ptaskapp', 'taskapp', '-N', '-B', '-e', query],
    { cwd: '..', encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], maxBuffer: 64 * 1024 * 1024 },
  ).trim();
}

async function xsrf(ctx: APIRequestContext): Promise<string> {
  let c = (await ctx.storageState()).cookies.find((x) => x.name === 'XSRF-TOKEN');
  if (!c) {
    await ctx.get('/api/auth/me');
    c = (await ctx.storageState()).cookies.find((x) => x.name === 'XSRF-TOKEN');
  }
  return c!.value;
}

function seedSql(ownerId: number, listId: number, prefix: string): string {
  return `SET SESSION cte_max_recursion_depth = 20000;
INSERT INTO tasks (owner_id, list_id, title, description, priority, status, done, due_date, created_at, updated_at)
WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 10000)
SELECT ${ownerId}, ${listId},
  CONCAT('${prefix} ', IF(n % 50 = 0, 'buy milk ', ''), IF(n % 97 = 0, 'Café ', ''), 'task ', n),
  IF(n % 7 = 0, CONCAT('details for ', n, IF(n % 51 = 0, ' with MILK', '')), NULL),
  ELT(1 + n % 4, 'NONE', 'LOW', 'MEDIUM', 'HIGH'),
  ELT(1 + n % 3, 'TODO', 'IN_PROGRESS', 'DONE'),
  (n % 3 = 2),
  IF(n % 4 = 0, NULL, DATE_ADD('2026-01-01', INTERVAL n % 400 DAY)),
  DATE_SUB('2026-10-19 00:00:00', INTERVAL n MINUTE),
  DATE_SUB('2026-10-19 00:00:00', INTERVAL n MINUTE)
FROM seq;
INSERT INTO tags (owner_id, list_id, name) VALUES (${ownerId}, ${listId}, 'Work');
INSERT INTO task_tags (task_id, tag_id)
SELECT t.id, g.id FROM tasks t JOIN tags g ON g.list_id = t.list_id AND g.name = 'Work'
WHERE t.list_id = ${listId} AND (t.id % 5 = 0);`;
}

test.describe('T3a AC16 performance (E2E_PERF=1)', () => {
  test.skip(!RUN_PERF, 'set E2E_PERF=1 to seed 20,000 tasks and time the first page');
  test.setTimeout(20 * 60_000);

  test('AC16: 10,000 tasks in one list: every sort/search/tab/tag first page < 300 ms', async () => {
    const ctx = await request.newContext({ baseURL: API });
    const stamp = `${Date.now()}`;
    const email = `qa-t3a-perf-${stamp}@example.com`;
    const reg = await ctx.post('/api/auth/register', {
      data: { displayName: `Perf ${stamp}`, email, password: 'password123' },
      headers: { 'X-XSRF-TOKEN': await xsrf(ctx) },
    });
    expect(reg.status()).toBe(201);
    const userId = ((await reg.json()) as { id: number }).id;
    const big = await ctx.post('/api/lists', { data: { name: 'Big' }, headers: { 'X-XSRF-TOKEN': await xsrf(ctx) } });
    expect(big.status()).toBe(201);
    const bigId = ((await big.json()) as { id: number }).id;
    const myId = Number(sql(`SELECT id FROM lists WHERE owner_id = ${userId} AND personal`));
    sql(seedSql(userId, bigId, 'Big'));
    sql(seedSql(userId, myId, 'Mine'));
    const counts = sql(`SELECT list_id, COUNT(*) FROM tasks WHERE owner_id = ${userId} GROUP BY list_id ORDER BY list_id`);
    const tagged = sql(`SELECT COUNT(*) FROM task_tags tt JOIN tags g ON g.id = tt.tag_id WHERE g.list_id = ${bigId}`);

    const rows: Record<string, unknown>[] = [];
    try {
      // warm up
      for (let i = 0; i < 5; i += 1) await ctx.get(`/api/tasks?list=${bigId}`);
      for (const sort of ['newest', 'oldest', 'due', 'priority', 'title']) {
        for (const filter of ['all', 'active', 'in_progress', 'done']) {
          for (const q of ['', 'milk', 'cafe', 'zzz-nothing']) {
            for (const tag of ['', 'Work']) {
              const qs = new URLSearchParams({ list: String(bigId), sort, filter });
              if (q) qs.set('q', q);
              if (tag) qs.set('tag', tag);
              const times: number[] = [];
              let body: { items: { listId: number }[]; total: number; nextCursor: string | null } | null = null;
              for (let i = 0; i < 3; i += 1) {
                const t0 = performance.now();
                const r = await ctx.get(`/api/tasks?${qs}`);
                const t = performance.now() - t0;
                expect(r.status()).toBe(200);
                body = await r.json();
                times.push(t);
              }
              expect(body!.items.every((x) => x.listId === bigId)).toBe(true);
              let page2: number | null = null;
              if (body!.nextCursor) {
                qs.set('cursor', body!.nextCursor);
                const t0 = performance.now();
                const r2 = await ctx.get(`/api/tasks?${qs}`);
                page2 = Math.round((performance.now() - t0) * 10) / 10;
                expect(r2.status()).toBe(200);
              }
              times.sort((a, b) => a - b);
              rows.push({
                sort,
                filter,
                q,
                tag,
                total: body!.total,
                median: Math.round(times[1] * 10) / 10,
                max: Math.round(times[2] * 10) / 10,
                page2,
              });
            }
          }
        }
      }
    } finally {
      const worst = [...rows].sort((a, b) => (b['max'] as number) - (a['max'] as number)).slice(0, 10);
      const over = rows.filter((r) => (r['max'] as number) >= 300 || ((r['page2'] as number | null) ?? 0) >= 300);
      mkdirSync(OUT, { recursive: true });
      writeFileSync(
        `${OUT}/ac16-timings.txt`,
        [
          `User ${userId} lists (list_id, tasks): ${counts.replace(/\n/g, '; ')}; Big list ${bigId} tagged Work ${tagged}`,
          JSON.stringify(rows),
          `WORST ${JSON.stringify(worst)}`,
          `over300 ${over.length} of ${rows.length}`,
        ].join('\n'),
      );
      console.log(`AC16 worst: ${JSON.stringify(worst.slice(0, 3))}; over300 ${over.length} of ${rows.length}`);
      // cleanup: deleting the account cascades lists -> tasks -> tags
      const del = await ctx.post('/api/account/delete', { data: { password: 'password123' }, headers: { 'X-XSRF-TOKEN': await xsrf(ctx) } });
      expect(del.status()).toBe(204);
      expect(sql(`SELECT COUNT(*) FROM tasks WHERE owner_id = ${userId}`)).toBe('0');
      await ctx.dispose();
      expect(over, 'combinations at or over 300 ms').toEqual([]);
      expect(rows).toHaveLength(160);
    }
  });
});
