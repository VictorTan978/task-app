# Next session: T1 — Tasks: priority, status and tags

Written 2026-10-16 at the end of A5. Start a new Claude Code session in `/Users/victor/task-app` and paste the prompt below.

## Prompt to paste

```
Read docs/next-session-T1-tasks-priority-status-tags.md and CLAUDE.md. You are the PM. Start T1 (tasks: priority,
status, tags) on the branch feature/tasks-priority-status-tags: draft specs/tasks-priority-status-tags.md from
specs/_TEMPLATE.md, using the "Open questions" in the handoff doc (with your recommendations), show me the spec and wait
for my answers and approval. After approval, go straight to contract and build without asking again (only stop for
approval gates or real questions).
```

---

## Where the project stands

| Part | Status |
|---|---|
| A1 account settings (`specs/account-settings.md`) | DONE, on `main` |
| A2 sessions: remember me, 12h max, log out other devices (`specs/sessions.md`) | DONE, on `main` |
| A3 email verification and password reset (`specs/email-auth.md`) | DONE, on `main` |
| A4 Sign in with Google (`specs/google-sign-in.md`) | DONE, on `main` |
| A5 roles and admin users (`specs/roles-admin.md`) | DONE, merged to `main` 2026-10-16 (310cd51) |
| **T1 tasks: priority, status, tags** | **next — branch `feature/tasks-priority-status-tags` already created from `main`** |
| T2 tasks: search, sort, pagination | later |
| T3 sharing / team lists | later |
| T4 due times, reminders, notifications, recurring tasks | later |
| T5 undo, trash, drag and drop, bulk actions | later |

Test baseline on `main` after A5: backend 644 tests, frontend 512 unit tests, lint and build clean, full e2e 178 passed
/ 19 skipped (plus the env-gated runs: `E2E_SHORT_SESSIONS`, `E2E_SHORT_LINKS`, `E2E_RATE_LIMIT`, `E2E_MAIL_DOWN`,
`E2E_GOOGLE_OFF`; `E2E_BACKEND_LOG` enables the audit-log checks). Contract `contracts/openapi.yaml` is at **0.9.0**.

## How the user wants us to work

- Follow `CLAUDE.md`: PM delegates to `backend-dev`, `frontend-dev`, `tester`; spec → contract (max 2 rounds) → build →
  test → max 3 fix rounds → final report in the CLAUDE.md format.
- **No pause before build:** once the spec is approved and the contract agreed, build straight away. Stop only for
  approval gates or real questions.
- **Git:** commit and push the feature branch after each change. Merge to `main` and push only when the user says
  **"merge"**, and only with green tests (A4 and A5 were fast-forwarded). Never force-push.
- Explain options in plain words with a recommendation; the user answers short ("1. yes 2. …"). They may add their own
  ideas (in A5 they added a `SUPER_ADMIN` role), so re-check the spec after their answers before asking for approval.
- When asking at an approval gate, give what / why / files / risks / reversible / alternative.
- Run the frontend and backend agents in parallel once the contract is agreed; the PM commits (agents don't).
- Tell agents not to use broad `pkill` patterns (in A5 a `pkill -f cat` may have killed unrelated user processes).

## What exists today that T1 builds on

- **Spec:** `specs/tasks.md` (DONE). Its "Out of scope" lists exactly what T1 adds: priority, multi-state status,
  tags. Keep every existing tasks AC working.
- **Database:** `tasks` (V2): `id`, `owner_id` (FK → users, `ON DELETE CASCADE`), `title` VARCHAR(200),
  `description` VARCHAR(2000), `done` BOOLEAN, `due_date` DATE, `created_at`, `updated_at`; index
  `ix_tasks_owner_done_created (owner_id, done, created_at DESC, id DESC)`. Collation `utf8mb4_0900_ai_ci`.
  **Next migration is V9.** Timestamps are `DATETIME(6)` UTC.
- **API (contract 0.9.0):** `GET /api/tasks?filter=all|active|done` (bare `Task[]`, sorted done asc, createdAt desc,
  id desc; unknown filter → all), `POST /api/tasks`, `GET/PUT/PATCH/DELETE /api/tasks/{id}`. `PUT` is a **full
  replacement** (title, description, dueDate, done all sent). `PATCH` **sets** `{done}`. Another user's task → 404.
- **Backend:** `backend/src/main/java/com/taskapp/task/` (`Task`, `TaskController`, `TaskService`, `TaskRepository`,
  `TaskFilter`, `dto/`).
- **Frontend:** `frontend/src/app/features/tasks/` (`tasks.models.ts`, `tasks-api.ts`, `tasks.service.ts`,
  `tasks.logic.ts`, `task-form.ts`, `tasks-page/`, `task-row/`, `task-add-form/`, `task-edit-dialog/`,
  `confirm-delete-dialog/`). Filter tabs All / Active / Done kept in the URL (`?filter=`). No optimistic updates.
  Plain SCSS, no UI library.
- **Admins can't see tasks** (A5 AC10) — that must stay true.

## Open questions for the spec (with recommendations)

1. **Priority values?** Recommend optional: `None` (default), `Low`, `Medium`, `High`. Shown as a small coloured badge
   on the row (nothing for None), set in the edit dialog.
2. **Status values?** Recommend `To do`, `In progress`, `Done`. The checkbox keeps working: ticking → Done, unticking →
   To do. Status is set in the edit dialog; "In progress" shows a badge on the row.
3. **What happens to `done`?** Recommend expand-then-contract: add `status`, backfill it from `done`
   (`done=true` → DONE, else TODO), and keep `done` in the API as a read-only mirror of `status == DONE` so `PATCH {done}`
   and existing clients still work. Dropping the `done` column is a later, separately approved migration.
4. **Filters?** Recommend tabs All / Active / In progress / Done (`?filter=in_progress`). "Active" = not Done (To do +
   In progress). "Overdue" still means not Done and past due.
5. **List order?** Recommend unchanged in T1 (not-done first, newest first). Sorting by priority belongs to T2.
6. **Tags: what are they?** Recommend free-text labels owned by each user (a user's tags are private): 1–30 characters,
   trimmed, unique per user ignoring case and accents, at most 10 tags per task. Typed in the edit dialog as chips
   (Enter or comma to add, pick from your existing tags as you type). No colours in T1.
7. **Tag filter?** Recommend yes, simple: clicking a tag on a row filters the list to that tag (`?tag=<name>`, combined
   with the status tab), with a "×" to clear it. Search across text stays in T2.
8. **Managing tags?** Recommend no separate page in T1: a tag disappears automatically when no task uses it. Renaming or
   deleting a tag everywhere is out of scope.
9. **Quick-add box?** Recommend unchanged: title only (no `#tag` or `!high` syntax). New tasks are None / To do / no tags.
10. **PUT is a full replacement — what about the new fields?** Recommend: `priority`, `status` and `tags` are optional in
    `PUT`; when missing they are left unchanged (so an old client can't wipe them). Backend confirms during the contract.

## Approval gates expected in T1

- **Migration V9:** `tasks.priority` (e.g. `VARCHAR(8) NOT NULL DEFAULT 'NONE'` + CHECK), `tasks.status`
  (`VARCHAR(16) NOT NULL DEFAULT 'TODO'` + CHECK, backfilled from `done`), a `tags` table (`id`, `owner_id` FK → users
  `ON DELETE CASCADE`, `name`, unique `(owner_id, name)`), a `task_tags` join table (FKs to tasks and tags,
  `ON DELETE CASCADE`), and an index change for the new filters. Backend proposes the exact SQL first.
  Keep the `done` column (expand-then-contract).
- **API change:** `Task` gains `priority`, `status`, `tags` (additive). Nothing renamed or removed.
- **No auth/security changes expected.** **No new dependency expected** (the tag-chip input is built by hand).

## Open backlog (not part of T1 unless the user asks)

From A5:
- V8 CHECK on `users.role` accepts a trailing space (`'ADMIN '`) because of `ascii_bin`; tightening needs a migration.
- `refreshMe()` callers (verify-email page, resend-verification, disconnect-Google dialog) don't navigate straight to
  `/login` on a one-shot 401 `ACCOUNT_DISABLED`; it happens on the next navigation.
- Share one CSRF-retry helper between `AdminService` and `AuthService`; move duplicated `.btn--small`,
  `.notice--action`, `.btn--danger` styles to `styles.scss`.
- `EmailTokenService.check()` looks the user up twice for reset tokens.
- Pre-existing eslint errors in `frontend/e2e/email-auth.spec.ts:182` and `frontend/e2e/password-fields.spec.ts:46`.
- Seen flaky once under gated runs (passed on rerun): `email-auth.spec.ts:289` (`E2E_SHORT_SESSIONS`),
  `google-sign-in.spec.ts:1378` (`E2E_SHORT_LINKS`).

Older:
- Reset-password email should say "set" for accounts with no password (A4).
- Timeouts and a short backoff when Google's discovery URL can't be reached (A4).
- Share one error-response writer between security filters; define the 405 body for GET on the Google start URL (A4).
- Add the `oidc` and `mailpit` Docker images to Dependabot (A4).
- Prettier formatting pass; flaky `frontend/e2e/mysql-regression.spec.ts:78`.
- Delete tokens before account delete; an outbox for emails; scheduled cleanup of expired tokens (A2/A3).

## Dev setup reminder

`docker compose up -d db mail oidc`, then `cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` (applies
new migrations; add `ADMIN_BOOTSTRAP_EMAIL=<email>` once to get a super admin), then `cd frontend && npm start`.
Emails: http://localhost:8025. Fake Google: http://localhost:8090. Admin page: http://localhost:4200/admin/users.
