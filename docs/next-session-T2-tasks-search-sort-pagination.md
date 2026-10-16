# Next session: T2 — Tasks: search, sort and pagination

Written 2026-10-16 at the end of T1. Start a new Claude Code session in `/Users/victor/task-app` and paste the prompt below.

## Prompt to paste

```
Read docs/next-session-T2-tasks-search-sort-pagination.md and CLAUDE.md. You are the PM. Start T2 (tasks: search,
sort, pagination) on the branch feature/tasks-search-sort-pagination: draft specs/tasks-search-sort-pagination.md from
specs/_TEMPLATE.md, using the "Open questions" in the handoff doc (with your recommendations), show me the spec and wait
for my answers and approval. After approval, go straight to contract and build without asking again (only stop for
approval gates or real questions).
```

---

## Where the project stands

| Part | Status |
|---|---|
| A1–A5 (account settings, sessions, email auth, Google sign-in, roles/admin) | DONE, on `main` |
| T1 tasks: priority, status, tags (`specs/tasks-priority-status-tags.md`) | DONE, merged to `main` 2026-10-16 (d0ed084) |
| **T2 tasks: search, sort, pagination** | **next — branch `feature/tasks-search-sort-pagination` already created from `main`** |
| T3 sharing / team lists | later |
| T4 due times, reminders, notifications, recurring tasks | later |
| T5 undo, trash, drag and drop, bulk actions | later |

Test baseline on `main` after T1: backend 828 tests, frontend 596 unit tests, lint and build clean, full e2e 200 passed
/ 19 skipped (env-gated). Contract `contracts/openapi.yaml` is at **0.10.0**. **Next migration is V10.**

Running the full e2e suite needs the backend started with every per-IP limit raised, e.g.
`RATE_LIMIT_{REGISTER,LOGIN,FORGOT_PASSWORD,RESET_PASSWORD,CHECK_RESET_TOKEN,VERIFY_EMAIL}_PER_IP_PER_HOUR=100000`
and `RATE_LIMIT_{GOOGLE_START,GOOGLE_CALLBACK}_PER_IP_PER_MINUTE=100000` (leave per-user/per-account limits at default;
some tests check them). The T1 e2e file needs `docker compose up -d db` (it uses `docker compose exec db mysql`).

## How the user wants us to work

- Follow `CLAUDE.md`: PM delegates to `backend-dev`, `frontend-dev`, `tester`; spec → contract (max 2 rounds) → build →
  test → max 3 fix rounds → final report in the CLAUDE.md format.
- **No pause before build:** once the spec is approved and the contract agreed, build straight away. Stop only for
  approval gates or real questions.
- **Git:** commit and push the feature branch after each change. Merge to `main` (fast-forward) and push only when the
  user says **"merge"**, and only with green tests. Never force-push.
- Explain options in plain words with a recommendation; the user answers short ("1. yes 2. …" or just "yes" = accept
  all recommendations). Re-check the spec after their answers before asking for approval.
- When asking at an approval gate, give what / why / files / risks / reversible / alternative.
- Run backend and frontend agents in parallel once the contract is agreed; the PM commits (agents don't).
- Tell agents never to use broad `pkill` patterns; stop processes by PID only. Tell them not to stop a backend the
  tester started.
- Ask the tester to run concurrency checks for anything that writes (T1 found a cross-user deadlock only under
  parallel load).

## What exists today that T2 builds on

- **Specs:** `specs/tasks.md`, `specs/tasks-priority-status-tags.md` (both DONE). All their ACs must keep passing.
- **Database:** `tasks` (`id`, `owner_id`, `title` VARCHAR(200), `description` VARCHAR(2000), `priority`, `status`,
  `done` (read-only mirror of status, kept for expand-then-contract), `due_date`, `created_at`, `updated_at`);
  `tags` (`owner_id`, `name` utf8mb4_0900_ai_ci, unique per owner); `task_tags`. Indexes:
  `ix_tasks_owner_done_created (owner_id, done, created_at DESC, id DESC)`,
  `ix_tasks_owner_status_created (owner_id, status, created_at DESC, id DESC)`.
  Collation `utf8mb4_0900_ai_ci` (case- and accent-insensitive).
- **API (0.10.0):** `GET /api/tasks?filter=all|active|in_progress|done&tag=<name>` returns a **bare `Task[]`**
  (no paging), sorted not-done first, then `created_at DESC, id DESC`. Unknown filter → all; unknown tag → `[]`.
  `GET /api/tags` → `[{name}]`. PUT/PATCH/POST/DELETE as in the T1 Decisions log.
- **Backend:** `backend/src/main/java/com/taskapp/task/` (`TaskRepository` uses derived queries plus one native
  query for the tag filter; tags for a list are loaded in one query; `DeadlockRetry` around PUT/DELETE).
- **Frontend:** `frontend/src/app/features/tasks/` (tabs and `?tag=` kept in the URL; after a save/tick a task that no
  longer matches the view disappears; no optimistic updates; plain SCSS, no UI library).
- **Admins can't see tasks** (A5 AC10) — must stay true.

## Open questions for the spec (with recommendations)

1. **What does search look at?** Recommend title and description (tags already have their own filter), case- and
   accent-insensitive "contains" match, `?q=` in the URL, combined with the tab and tag filter. Max 100 characters;
   blank = no search.
2. **How is search done in the database?** Recommend plain `LIKE '%…%'` (with `%`, `_` and `\` escaped) on the
   existing collation: simple, matches substrings, fine for personal lists. MySQL FULLTEXT can't do substrings and
   would be a heavier migration.
3. **When does the list search?** Recommend as you type, 300 ms after the last keystroke, plus Enter; a "×" clears it.
   Empty result: "No tasks match "<text>"".
4. **Sort options?** Recommend a "Sort" select: Newest (default, today's order), Oldest, Due date (soonest first,
   no date last), Priority (High → None), Title A–Z. `?sort=` in the URL; unknown value → default. Ties broken by
   newest first, then id.
5. **Do Done tasks stay at the bottom when sorting?** Recommend yes for All (not-done group first, each group sorted
   by the chosen option), so finished work never mixes in.
6. **Pagination style?** Recommend a "Load more" button (50 tasks per page) instead of page numbers: simpler on phone,
   keeps the list in one place. Show "Showing 50 of 312".
7. **API shape for pages?** Today the list is a bare array. Recommend a page object `{ items, total, nextCursor }`
   (or `page`/`size`), which is a **breaking change** to `GET /api/tasks` → approval gate (only our own frontend uses
   it). Alternative: keep the bare array and send the total in a header (`X-Total-Count`) with `limit`/`offset`
   params — non-breaking but less clear. Backend proposes cursor vs offset during the contract (cursor is stable when
   tasks are added or ticked while paging; offset is simpler).
8. **What happens to loaded pages after an edit, tick, add or delete?** Recommend: keep the loaded items and update
   them in place (as today); the total adjusts; no full reload. A new quick-add task appears at the top only when the
   sort is Newest.
9. **Indexes?** Recommend the backend checks query plans for each sort with ~10k tasks per user and proposes only the
   indexes that are needed (migration V10 → approval gate). No schema change if plans are fine.
10. **Drop the `done` column now?** Recommend **no** — keep it out of T2; it gets its own small, separately approved
    migration later (first move the index and sort onto `status`).

## Approval gates expected in T2

- **API change:** `GET /api/tasks` response shape changes (if a page object is chosen) → breaking, needs approval.
- **Migration V10** only if new indexes are needed. Backend shows the exact SQL and the query plans first.
- **No auth/security changes expected.** **No new dependency expected.**

## Open backlog (not part of T2 unless the user asks)

From T1:
- Tag "TOO_MANY" uses a Java approximation of the collation: 10 names MySQL counts as equal (ß/ss) can be over-counted.
- Tag filter bar could fetch `/api/tags` to show the stored spelling when no loaded task has the tag.
- MySQL CHECK errors (3819) aren't mapped to `DataIntegrityViolationException` → would be a 500 (unreachable today).
- Hibernate logs a retried deadlock at ERROR before `DeadlockRetry`'s WARN.
- Dialog: Save stays blocked after a server field error until that field changes (all fields).
- `core/auth/auth.models.ts` `FieldErrorCode`/`FieldName` lack the 0.10.0 codes (tasks feature has its own types).
- Document the per-IP rate-limit overrides needed for the full e2e run.
- `AdminIntegrationTest` "no private data" check was a no-op before T1 (fixed in T1).

From A5 and older: see `docs/next-session-T1-tasks-priority-status-tags.md` ("Open backlog").

## Dev setup reminder

`docker compose up -d db mail oidc`, then `cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` (applies
new migrations; stop with Ctrl+C), then `cd frontend && npm start`. `docker compose down` keeps the data
(named volume `mysql-data`); only `docker compose down -v` deletes it.
Emails: http://localhost:8025. Fake Google: http://localhost:8090. Admin page: http://localhost:4200/admin/users.
