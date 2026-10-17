# Next session: T3a — Lists (build), then T3b — Sharing

Written 2026-10-17. Start a new Claude Code session in `/Users/victor/task-app` on branch
`feature/sharing-team-lists` and paste the prompt below.

## Prompt to paste

```
Read docs/next-session-T3a-lists.md, specs/sharing-team-lists.md and CLAUDE.md. You are the PM.
We are on feature/sharing-team-lists building T3a (Lists, Part A, AC1-AC17). The spec is approved and
contract 0.12.0 is agreed (see the spec's Decisions log). Three approval gates are waiting for me:
Gate 1 migration V10 (exact SQL in this doc), Gate 2 the access change, Gate 3 sign-up creating
"My tasks" (touches AuthService/GoogleAccountService). Re-show me the three gates in short form
(what / why / files / risks / reversible / alternative) and wait for my yes.
After my yes: backend-dev writes contract 0.12.0 into contracts/openapi.yaml first, then backend-dev and
frontend-dev build in parallel (pass each the full contract and the plan in this doc), then tester checks
AC1-AC17 including the AC13 concurrency stress test, then a full green rerun (backend, lint, unit, build,
full e2e), then the final report, and wait for "merge". T3b starts only after T3a is merged.
```

---

## Where the project stands

| Part | Status |
|---|---|
| A1–A5, T1, T2 | DONE, on `main` |
| Bug: task write racing a delete → 500 | DONE, merged 2026-10-17 (`a0aac6e`) |
| e2e test fixes (hard-coded `localhost:4200` in `browserlessGet`; AC13 processlist flake) | DONE, merged 2026-10-17 (`0991297`) |
| **T3a Lists** (`specs/sharing-team-lists.md` Part A) | **spec approved, contract 0.12.0 agreed, waiting for gates 1–3** |
| T3b Sharing (Part B) | after T3a is merged; new branch from `main`, contract 0.13.0, migration V11 |
| T4, T5 | later |

Branch `feature/sharing-team-lists` = `main` (`0991297`) + spec commits, merged with `main` at `011b97d`.
Nothing of T3a is built yet; no code changed on the branch.

**Test baseline on `main` (`0991297`):** backend 887 tests; frontend lint clean, 690 unit tests, build clean; full
e2e has **240 tests**: 224 passed, 15 skipped (env-gated), 1 timing flake under full load (`email-auth.spec.ts:289`,
passes alone). Contract `contracts/openapi.yaml` is at **0.11.0**. **Next migration is V10.**

Check the arithmetic of every test count before reporting it (passed + failed + skipped = 240).

## How to run the full e2e suite (unchanged from before)

The user may have their own backend (8080) and `ng serve` (4200) running. Don't stop them; the tester runs its own stack.
- Docker Desktop must be running; `docker compose up -d db mail oidc`.
- Backend on 8081: `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8081 APP_BASE_URL=http://localhost:4201` plus these at
  100000: `RATE_LIMIT_{REGISTER,FORGOT_PASSWORD,RESET_PASSWORD,CHECK_RESET_TOKEN,VERIFY_EMAIL}_PER_IP_PER_HOUR`,
  `RATE_LIMIT_{LOGIN,GOOGLE_START,GOOGLE_CALLBACK}_PER_IP_PER_MINUTE`, `RATE_LIMIT_ADMIN_ACTIONS_PER_USER_PER_MINUTE`.
- `cd frontend && npx ng serve --port 4201 --proxy-config <json: /api → http://localhost:8081>`, then
  `npx playwright test --config <copy of playwright.config.ts with baseURL 4201 and absolute testDir>` with
  `E2E_BASE_URL=http://localhost:4201` **and** `E2E_BACKEND_LOG` / `BACKEND_LOG` set to the 8081 backend log (without
  them 4 audit tests skip and you get 19 skips instead of 15). Ready-made copies:
  `frontend/test-results/e2e-base-url-rerun/playwright.4201.config.ts` and `proxy.8081.json` (gitignored).
- Run the Maven suite and e2e one after the other, not at the same time (overlap caused hangs).
- Backend tests: `./mvnw clean test` (~1.5 h with the known ~30 s forked-JVM exit delay).
- Stop processes by PID only.

## How the user wants us to work

- Follow `CLAUDE.md`: PM delegates; spec → contract (max 2 rounds) → build → test → max 3 fix rounds → report.
- **No pause before build:** after gates are approved, build straight away; stop only for gates or real questions.
- **Git:** commit and push the branch after each change; merge to `main` (fast-forward) and push only when the user
  says **"merge"**, and only after a **full** green rerun (backend, lint, unit, build, **full** e2e). Bring `main` into
  a feature branch with a merge, never rebase/force-push.
- Plain words, a recommendation per option; the user answers short ("1. yes 2. …" or "yes").
- At a gate give what / why / files / risks / reversible / alternative.
- Backend and frontend agents run in parallel once the contract is agreed; agents don't commit, the PM does.
- Ask the tester for concurrency checks on anything that writes (T1, T2 and the race bug were all found that way).

---

## Contract 0.12.0 (agreed in round 1 — backend writes it into `contracts/openapi.yaml` first)

Full decisions are in the spec's Decisions log (2026-10-17 rows). Summary:

**Schemas**
- `TaskList { id: int64, name: string (1–100), personal: boolean }` — returned by `GET /api/lists`, `POST`, `PATCH`.
- `TaskListDetails` = `TaskList` + `taskCount: int64` (all tasks, any status) — returned only by `GET /api/lists/{id}`.
  **PM decision: no `taskCount` on `GET /api/lists`** (the backend's first draft had it on every item; drop it).
- `CreateListRequest { name }`, `RenameListRequest { name }` — `name` follows the **task-title rules** (same trimming
  and length counting as titles), then 1–100. Errors: field `name`, `REQUIRED` / `TOO_LONG`. Duplicate names allowed.
- `Task` gains `listId: int64` (required, always present). `CreateTaskRequest` and `ReplaceTaskRequest` gain optional
  `listId` (missing/null: create → "My tasks"; PUT → unchanged; another of my lists → move).
- No `role` / `memberCount` yet (0.13.0, additive).

**Endpoints**
- `GET /api/lists` → `TaskList[]`, unpaged, "My tasks" first, then by name (`utf8mb4_0900_ai_ci`), then id.
- `GET /api/lists/{id}` → `TaskListDetails`; 400 `MALFORMED_REQUEST` (non-integer); 404 `LIST_NOT_FOUND`.
- `POST /api/lists` → 201 + `Location`; 400 `VALIDATION_FAILED` (`name`); 409 `LIST_LIMIT` (51st owned list, no
  `errors` array). No rate limit.
- `PATCH /api/lists/{id}` (rename, "My tasks" too) → 200; 400; 404 `LIST_NOT_FOUND`.
- `DELETE /api/lists/{id}` → 204 (list, tasks, task_tags, tags); 409 `PERSONAL_LIST`; 404 `LIST_NOT_FOUND`.
- `GET /api/tasks?list=` and `GET /api/tags?list=`: missing/empty = "My tasks"; anything not mine (unknown, other
  user's, deleted, non-numeric, 0) → 404 `LIST_NOT_FOUND` (checked before `q`/`cursor`). Cursor fingerprint includes
  the resolved list id; cursor `VERSION` 1 → 2.
- `PUT /api/tasks/{id}` with `listId` = move (no separate move endpoint). Tags after the move = `tags` if sent, else the
  current names; reused (target spelling, ignoring case/accents) or created in the target; source's now-unused tags
  deleted; target over 200 → 400 `tags` / `TAG_LIMIT`, nothing changes. Task gone → 404 `NOT_FOUND` (checked first);
  target list gone → 404 `LIST_NOT_FOUND`. `createdAt` unchanged.
- `TAG_LIMIT` message: "A list can have at most 200 tags" (update the frontend unit tests and
  `e2e/tasks-priority-status-tags.spec.ts` that assert the old text). On a move the frontend shows its own text naming
  the target list.

**One-off effects at deploy (accepted):** old cursors give `INVALID_CURSOR` once; `GET /api/tasks` / `/api/tags`
without `list` return only "My tasks".

## Gate 1 — V10 SQL (tested by backend-dev on a throwaway copy of dev data, ~0.5 s, then dropped)

```sql
-- Lists (spec: specs/sharing-team-lists.md, T3a). Expand only: tasks.owner_id and tags.owner_id stay.
-- At most one personal list per user via a functional unique index (NULL for non-personal lists never collides);
-- "at least one" comes from the sign-up transactions and this backfill.
CREATE TABLE lists (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id   BIGINT       NOT NULL,
    name       VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    personal   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_lists_owner_name (owner_id, name, id),
    UNIQUE INDEX uk_lists_one_personal_per_owner ((IF(personal, owner_id, NULL))),
    CONSTRAINT fk_lists_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_lists_name_length CHECK (char_length(name) BETWEEN 1 AND 100)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO lists (owner_id, name, personal, created_at)
SELECT id, 'My tasks', TRUE, created_at FROM users ORDER BY id;

ALTER TABLE tasks ADD COLUMN list_id BIGINT NULL AFTER owner_id;
UPDATE tasks t JOIN lists l ON l.owner_id = t.owner_id AND l.personal = TRUE SET t.list_id = l.id;
ALTER TABLE tasks
    MODIFY COLUMN list_id BIGINT NOT NULL,
    ADD INDEX ix_tasks_list_done_created (list_id, done, created_at DESC, id DESC),
    ADD INDEX ix_tasks_list_status_created (list_id, status, created_at DESC, id DESC),
    ADD CONSTRAINT fk_tasks_list FOREIGN KEY (list_id) REFERENCES lists (id) ON DELETE CASCADE;

ALTER TABLE tags ADD COLUMN list_id BIGINT NULL AFTER owner_id;
UPDATE tags g JOIN lists l ON l.owner_id = g.owner_id AND l.personal = TRUE SET g.list_id = l.id;
ALTER TABLE tags
    MODIFY COLUMN list_id BIGINT NOT NULL,
    ADD CONSTRAINT uk_tags_list_name UNIQUE (list_id, name),
    ADD INDEX ix_tags_owner (owner_id),
    ADD CONSTRAINT fk_tags_list FOREIGN KEY (list_id) REFERENCES lists (id) ON DELETE CASCADE;
ALTER TABLE tags DROP INDEX uk_tags_owner_name;

-- Without fresh statistics the optimizer picked the done index for ?filter=in_progress in testing.
ANALYZE TABLE lists, tasks, tags;
```

Probe results: 0 tasks without a list, 0 tasks on another owner's list, every user got "My tasks"; a second personal
list → error 1062; same tag name in two lists works; list and user deletes cascade correctly; EXPLAIN uses the new
`list_id` indexes with no filesort. Risks: the FK `ALTER`s use `ALGORITHM=COPY` (table copy, writes wait; runs at
startup); MySQL DDL isn't transactional (partial failure → manual cleanup + `flyway repair`, never `clean`).
Reversible only until users create a second list or reuse a tag name across lists. `taskapp_app` needs no new grant.

## Gate 2 — access change (backend plan)

- `TaskRepository.findByIdAndOwnerId` → `findAccessible(id, userId)` (join `lists`, `l.owner_id = :userId`).
- New `ListRepository` (`findOwned`, `findPersonal`, `lockOwned … FOR UPDATE`, `shareOwned … FOR SHARE`,
  `countByOwnerId`, `rename` as a `@Modifying` UPDATE returning a row count — 0 → 404, never an optimistic-lock
  exception), `ListService` (`requireOwned(userId, list)` → else 404), `ListController`.
- `TaskSearchRepositoryImpl`: `WHERE t.owner_id = :ownerId` → `WHERE t.list_id = :listId`; `TaskSearchCriteria.ownerId`
  → `listId`; `TaskListQuery` fingerprint gets the resolved list id.
- `TagRepository` / `TagService` / `TagController`: list-scoped; `lockOwner` removed; `TagNames.MAX_PER_USER` →
  `MAX_PER_LIST`. **The app always writes `tags.owner_id` = the list's owner** (not the acting user) so that in T3b a
  member's account deletion can never cascade-delete list tags, and V11 needs no tag change.

**Concurrency design (AC13):** lock order everywhere: users row → lists rows in ascending id → task/tag/link rows.
- Write = (1) a separate short read to locate the task's list (else 404), then (2) a transaction: users row FOR SHARE
  (replaces `lockOwner`; avoids a deadlock with account deletion), the list(s) FOR UPDATE lowest id first (A gone →
  404 `NOT_FOUND`, target gone → `LIST_NOT_FOUND`), re-read the task by `id` and `list_id`; if it moved meanwhile, roll
  back and restart (max 3, then 404); then tags/move; all inside `DeadlockRetry.run`.
- `PATCH`, and `PUT` without `tags`/`listId`: unlocked as today; `saveOrNotFound` (from `a0aac6e`) turns a 0-row update
  after a list-delete cascade into 404.
- `POST /api/tasks`: list FOR SHARE before the insert (else an FK error → 500).
- List delete: users FOR SHARE → list FOR UPDATE → owner and `PERSONAL_LIST` checks → `DELETE` (cascade), in
  `DeadlockRetry`.
- 50-list limit: users row FOR UPDATE → count → 409 or insert.
- Safety net: map a `DataIntegrityViolationException` naming `fk_tasks_list` / `fk_tags_list` to 404, not 500.

## Gate 3 — sign-up creates "My tasks"

- `AuthService.register` is not transactional today: wrap the user insert plus `ListService.createPersonal(userId)`
  (Propagation.MANDATORY) in a `TransactionTemplate`; keep the `DataIntegrityViolationException` → conflict mapping
  inside; send the verification email (`emailJobs.sendVerification`) after the commit.
- `GoogleAccountService.signInOnce` already runs in a READ COMMITTED template with retry: call `createPersonal`
  right after `users.saveAndFlush`.
- `AccountService.deleteNow` and the admin delete need no change (cascade).
- Expect test churn: many repository tests insert users directly and need a fixture that also creates "My tasks".
- Alternative (not recommended): create "My tasks" lazily on first use, so no auth change, but every path must cope
  with a user who has no list yet.

## Frontend build plan (frontend-dev, round 1)

- New `features/lists/`: `lists.models.ts`, `lists-api.ts` / `lists.service.ts`, `lists.store.ts` (root signals store:
  `lists`, `loading`, `error`, `personal`, `byId`; `load()`/`refresh()`), `list-picker/` (desktop sidebar + phone
  `<select>`, "+ New list" inline form with field / `LIST_LIMIT` errors), `list-menu/` ("⋯": Rename inline, Delete),
  `delete-list-dialog/` (reads `GET /api/lists/{id}` on open for `taskCount`; "Delete "Home" and its 12 tasks?").
- Route `lists/:id` → existing `TasksPage`; `/tasks` = "My tasks", sends no `list` (old deep links unchanged). Picker
  links have no query params, so tab/tag/search/sort reset. One stream combines `paramMap` + `queryParamMap`; on a
  list change: reset paging (`emptyList()`), cancel in-flight loads, clear search/banners/notices, close dialogs, reload
  tags. Non-numeric `:id` → "List not found" with no request; API 404 `LIST_NOT_FOUND` → "List not found" + link to
  "My tasks" + refresh the store.
- Lists are refetched: after sign-in, after create/rename/delete, on `LIST_NOT_FOUND`, on Retry — not per navigation.
- Edit dialog: "List" select (my lists, preselect `task.listId`, disabled with a note if the store failed); send
  `listId` only when changed; `TAG_LIMIT` on a move shown next to the List select; tag suggestions stay the current
  list's. `task-list.ts` gains `moveOut(state, id)` (like `removeTask`: drop row, total −1, add to `deleted`).
- Changed: `app.routes.ts`, `tasks-page.*`, `tasks.service.ts`, `tasks.models.ts`, `task-edit-dialog.ts`,
  `task-form.ts`, `task-list.ts`. No auth/interceptor/npm changes. Reuse the app's breakpoint if one exists, else 640px.
- `tasks-page.ts` is already ~430 lines; frontend-dev may move the list header / route stream into small helpers only
  if the PM agrees (counts as scope — say so in the report).

## E2E scenarios for the tester (AC1–AC17)

"My tasks" for new users (email and Google) and renamable; create list (100/101 chars, blank, 51st refused); picker
order, desktop sidebar vs phone dropdown; switching resets tab/tag/search/sort and page 1; old `/tasks?filter=…&tag=…&q=…&sort=…`
deep links; `/lists/<other user's id>` and `/lists/999999` → "List not found" (API 404); delete with count, cancel,
confirm → "My tasks", no Delete on "My tasks"; tags per list (same name in two lists, no cross-suggestion/filter);
move leaves the view, total adjusts, tags appear in the target; move over 200 tags refused, nothing changes; move to a
list deleted in another tab; paging inside a list with > 50 tasks; migration kept every existing user's tasks and tags
(AC9); account deletion removes lists (AC12); admins see no lists (AC14); all T1/T2/tasks ACs still pass (AC15);
10k tasks in one list < 300 ms first page (AC16); no console errors.
**AC13 stress script** (parallel, many rounds, never a 500, never over a limit): tag writes and moves in one list; move
A→B racing B→A; task edit/move racing a delete of its list; two creates racing at the 50-list limit; plus the old
PATCH/PUT/DELETE race (`a0aac6e`) still clean.

## T3b reminders (after T3a is merged)

New branch from `main`; contract 0.13.0 (adds `role`, `memberCount` on `TaskList`; members; invitations); migration
V11 (`list_members`, `list_invitations`, `tasks.owner_id` nullable `ON DELETE SET NULL`); access becomes membership.
Spec Part B, AC18–AC34. Invites never reveal whether an account exists (AC18).

## Open backlog (not part of T3 unless the user asks)

New this session:
- `email-auth.spec.ts:289` timed out once under full load while closing its browsers (all checks had passed; passes
  alone). Watch it.
- Shared `frontend/e2e/env.ts` for the base URL (several specs repeat it inline).
- `TaskService.deleteOnce`: optional defensive `catch (ObjectOptimisticLockingFailureException)` for symmetry (not
  reachable today; the owner lock serializes deletes).
- After T3: drop the unused `ix_tasks_owner_status_created`, then `tags.owner_id` and (after T3b) consider
  `tasks.owner_id`; optional composite FK `tags (list_id, owner_id) → lists (id, owner_id)`.

From T2 and older: see `docs/next-session-T3-sharing-team-lists.md` ("Open backlog") — the `localhost:4200` item there
is now fixed.

## Dev setup reminder

Docker Desktop running, `docker compose up -d db mail oidc`, then
`cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` (applies new migrations — **V10 will run on your dev
database the first time you start the backend after T3a is built**), then `cd frontend && npm start`.
Emails: http://localhost:8025. Fake Google: http://localhost:8090.
