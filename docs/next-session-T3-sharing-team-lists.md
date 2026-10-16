# Next session: bug fix, then T3 — Sharing and team lists

Written 2026-10-16 at the end of T2. Start a new Claude Code session in `/Users/victor/task-app` and paste the prompt below.

## Prompt to paste

```
Read docs/next-session-T3-sharing-team-lists.md and CLAUDE.md. You are the PM.
Step 1: run /bug for the "write racing delete → 500" bug described in the handoff doc, on the branch
fix/task-write-race-500 (already created from main). Reproduce it first, fix it, verify with the tester, then give me
the report and wait for "merge".
Step 2 (after the bug is merged, or if I say so): start T3 (sharing / team lists) on a new branch
feature/sharing-team-lists from main: draft specs/sharing-team-lists.md from specs/_TEMPLATE.md, using the "Open
questions" in the handoff doc (with your recommendations), show me the spec and wait for my answers and approval.
After approval, go straight to contract and build without asking again (only stop for approval gates or real
questions).
```

---

## Where the project stands

| Part | Status |
|---|---|
| A1–A5 (account settings, sessions, email auth, Google sign-in, roles/admin) | DONE, on `main` |
| T1 tasks: priority, status, tags (`specs/tasks-priority-status-tags.md`) | DONE, on `main` |
| T2 tasks: search, sort, pagination (`specs/tasks-search-sort-pagination.md`) | DONE, merged to `main` 2026-10-16 (140dee2) |
| **Bug: task write racing a delete → 500** | **next — branch `fix/task-write-race-500` already created from `main` (holds this doc)** |
| **T3 sharing / team lists** | **after the bug** |
| T4 due times, reminders, notifications, recurring tasks | later |
| T5 undo, trash, drag and drop, bulk actions | later |

Test baseline on `main` after T2: backend 884 tests, frontend 690 unit tests, lint and build clean, full e2e
225 passed / 15 skipped (env-gated). Contract `contracts/openapi.yaml` is at **0.11.0**. **Next migration is V10.**

## How to run the full e2e suite

The user often has their own backend (8080) and `ng serve` (4200) running. **Don't stop them**; the tester runs its own
stack:
- `docker compose up -d db mail oidc`
- Backend on 8081: `SPRING_PROFILES_ACTIVE=dev SERVER_PORT=8081 APP_BASE_URL=http://localhost:4201` plus raised
  limits (100000): `RATE_LIMIT_{REGISTER,FORGOT_PASSWORD,RESET_PASSWORD,CHECK_RESET_TOKEN,VERIFY_EMAIL}_PER_IP_PER_HOUR`,
  `RATE_LIMIT_{LOGIN,GOOGLE_START,GOOGLE_CALLBACK}_PER_IP_PER_MINUTE` (login is per **minute**),
  `RATE_LIMIT_ADMIN_ACTIONS_PER_USER_PER_MINUTE`. Leave other per-user/per-account limits at default.
- `cd frontend && npx ng serve --port 4201 --proxy-config <json sending /api to http://localhost:8081>`, then
  `npx playwright test --config <copy of playwright.config.ts with baseURL 4201 and absolute testDir>` with
  `E2E_BASE_URL=http://localhost:4201`. Keep the proxy/config copies in the scratchpad or `test-results/`, never edit
  `frontend/playwright.config.ts`.
- Backend tests: `./mvnw clean test` (an IDE writes into `backend/target`).
- Stop processes by PID only, never broad `pkill`.

## How the user wants us to work

- Follow `CLAUDE.md`: PM delegates to `backend-dev`, `frontend-dev`, `tester`; spec → contract (max 2 rounds) → build →
  test → max 3 fix rounds → final report in the CLAUDE.md format.
- **No pause before build:** once the spec is approved and the contract agreed, build straight away. Stop only for
  approval gates or real questions.
- **Git:** commit and push the branch after each change. Merge to `main` (fast-forward) and push only when the user
  says **"merge"**, and only after a green full rerun (backend, lint, unit, build, full e2e). Never force-push.
- Explain options in plain words with a recommendation; the user answers short ("1. yes 2. …" or just "yes").
  Re-check the spec after their answers before asking for approval.
- At an approval gate give what / why / files / risks / reversible / alternative.
- Run backend and frontend agents in parallel once the contract is agreed; the PM commits (agents don't).
- Ask the tester for concurrency checks on anything that writes (T1 and T2 both found real issues only under
  parallel load).

## Step 1 — the bug

**Found by the T2 concurrency check; present before T2 (not a T2 regression).**
- **What:** when the same task gets `PATCH {status}`, `PUT` and `DELETE` at the same moment, a PATCH or PUT can
  return **500** instead of 404 (or success). 12 of 300 tries in a stress script; 19 of ~30k writes in a 60 s mixed
  run. Plain PATCH vs DELETE alone gave 0/150.
- **Error:** `ObjectOptimisticLockingFailureException: Row was updated or deleted by another transaction`, logged as
  "Unhandled exception", from `TaskService.setStatus` (PATCH, ~line 149) and `TaskService.replaceOnce` (PUT, ~line
  137) in `backend/src/main/java/com/taskapp/task/TaskService.java`. `DeadlockRetry` only covers deadlocks.
- **Expected:** 404 `NOT_FOUND` when the task was deleted, otherwise the write succeeds (last write wins, as today).
- **Repro script** from T2 (may be gone if the scratchpad was cleaned): `race.mjs` — create a task, fire the four
  requests in parallel, repeat.
- Likely backend-only. Fix must keep RFC 7807 errors and the contract (404 is already documented for these
  endpoints). Matters more for T3, where several people edit the same tasks.

## Step 2 — T3: what exists today that it builds on

- **Specs:** `specs/tasks.md`, `specs/tasks-priority-status-tags.md`, `specs/tasks-search-sort-pagination.md` (all
  DONE). All their ACs must keep passing. `specs/auth.md` decided "personal lists only, no sharing in v1".
- **Database:** `tasks` (`id`, `owner_id`, `title`, `description`, `priority`, `status`, `done` (read-only mirror of
  status, expand-then-contract), `due_date`, `created_at`, `updated_at`); `tags` (per owner, unique name ignoring case
  and accents, ≤200 per user); `task_tags`. Indexes on `(owner_id, done, created_at, id)` and
  `(owner_id, status, created_at, id)`. Collation `utf8mb4_0900_ai_ci`.
- **API (0.11.0):** `GET /api/tasks?filter&tag&q&sort&cursor` → keyset-paged `TaskPage { items, total, nextCursor }`
  (cursor encodes the sort key + a hash of filter/sort/tag/q); POST/GET/PUT/PATCH/DELETE `/api/tasks/{id}`;
  `GET /api/tags`. Another user's task → 404.
- **Backend:** `backend/src/main/java/com/taskapp/task/` (`TaskSearchRepositoryImpl` native keyset query,
  `TaskCursor`, `TagService` with an owner lock for tag writes, `DeadlockRetry`).
- **Frontend:** `frontend/src/app/features/tasks/` (`task-list.ts` holds paged state and in-place placement;
  URL holds `filter`, `tag`, `q`, `sort`).
- **Email:** the backend sends email (Mailpit in dev) — usable for invites.
- **Admins can't see tasks** (A5 AC10) — must stay true.

## Open questions for the T3 spec (with recommendations)

1. **What is shared — whole lists or single tasks?** Recommend **lists**: introduce a "list" (e.g. "Home",
   "Team X"). Every user gets a personal list "My tasks" holding all existing tasks; they can create more lists and
   share any list they own. Sharing single tasks is messier (whose tags? whose list does it show in?).
2. **How do you invite someone?** Recommend **by email address of an existing account**; the invitee gets an email and
   sees the list after accepting (or declining) an invite on a new "Invitations" spot. Inviting people without an
   account → later. Alternative: add members directly without accepting (simpler, but people get lists they didn't
   want).
3. **Roles?** Recommend **Owner and Member**: members can add, edit, tick and delete tasks; only the owner can rename
   or delete the list, invite and remove members. A read-only "Viewer" role → later.
4. **Leaving and removing:** a member can leave a list; the owner can remove a member. Their tasks stay in the list.
   Recommend: the owner can't leave without deleting the list (no ownership transfer in T3).
5. **Deleting a list:** recommend owner only, with a confirm dialog that says how many tasks will be deleted; the
   personal "My tasks" list can't be deleted.
6. **Account deletion:** lists you own are deleted with your account (members lose them); in lists you're a member of,
   your membership is removed. Recommend showing this in the delete-account warning.
7. **Tags in shared lists:** tags are per user today. Recommend **tags belong to the list** (everyone in a shared
   list sees and uses the same tags; the 200 limit becomes per list). Needs a migration that moves existing tags onto
   each user's personal list.
8. **Who did what:** recommend showing **"Added by <name>"** on tasks in shared lists, plus an optional **assignee**
   (any member) with an "Assigned to me" filter? Recommend **"added by" only in T3; assignee later (T4/T5)**.
9. **Switching lists in the UI:** recommend a list picker (sidebar on desktop, dropdown on phone) and the list in the
   URL (`/lists/<id>`, with `/tasks` → personal list). Search/sort/pages work inside one list; an "all lists" view →
   later.
10. **Moving a task between lists:** recommend yes, in the edit dialog, between lists you belong to; tags that don't
    exist in the target list are created there.
11. **Two people editing the same task:** recommend **last write wins** (as today) for T3, after the Step 1 fix makes
    "edited vs deleted" safe; a conflict warning (409 with a version) → later. Alternative: add a version check now.
12. **Live updates:** recommend **none in T3** — changes from others appear on reload or "Load more". Live updates
    (SSE/WebSocket) → later.
13. **Limits:** recommend max 50 lists per user, 20 members per list, 20 pending invites per list; rate-limit invites
    per user.
14. **Privacy:** members see each other's display name and email inside the list only. Non-members of a list get 404
    for its tasks (never 403, so ids don't leak). Admins still can't see any lists or tasks.
15. **API shape:** recommend new `/api/lists`, `/api/lists/{id}/members`, `/api/invitations`; tasks gain `listId`;
    `GET /api/tasks` gains `list=<id>` (default = personal list) so old calls keep working. Backend proposes the
    contract (0.12.0).

## Approval gates expected in T3

- **Migration V10** (lists, list members, invitations, `tasks.list_id` backfilled to each user's personal list, tags
  moved to lists, new indexes). Backend shows the exact SQL first. `tasks.owner_id` stays (expand-then-contract;
  becomes "created by").
- **Authorization change:** access moves from "owner of the task" to "member of the task's list" → touches
  authorization, needs approval.
- Possibly **renaming/repurposing** `owner_id` or changing tag uniqueness → needs approval.
- No new dependency expected (email already exists).

## Open backlog (not part of T3 unless the user asks)

From T2:
- Old list code no longer used: frontend `applyTask` / re-sort path in `tasks.logic.ts`; backend
  `TaskFilter.statuses()`.
- Duplicate helpers: LIKE escaping (`UserRepository.containsPattern` / `TaskSearchRepositoryImpl.containsPattern`);
  accent stripping (`searchKey` in `tasks.logic.ts` / `tagKey` in `task-tags.ts`).
- Move the list grouping and indexes from `done` onto `status` before dropping `done`.
- 320 px phone: the task tabs wrap ("Done" on its own line).
- `frontend/e2e/tasks-priority-status-tags.spec.ts:1114` hard-codes `http://localhost:4200`.
- Surefire sometimes waits 30 s for the forked test JVM to exit (a thread stays alive after tests).
- Seen flaky once under full parallel load (passed on rerun): `email-auth.spec.ts:400`,
  `security-logging.spec.ts:36`.

From T1 and older: see `docs/next-session-T2-tasks-search-sort-pagination.md` ("Open backlog").

## Dev setup reminder

`docker compose up -d db mail oidc`, then `cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` (applies
new migrations), then `cd frontend && npm start`. `docker compose down` keeps the data (named volume `mysql-data`);
only `docker compose down -v` deletes it.
Emails: http://localhost:8025. Fake Google: http://localhost:8090. Admin page: http://localhost:4200/admin/users.
