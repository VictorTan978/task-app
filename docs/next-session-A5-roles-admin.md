# Next session: A5 — Roles and admin users

Written 2026-10-15 at the end of A4. Start a new Claude Code session in `/Users/victor/task-app` and paste the prompt below.

## Prompt to paste

```
Read docs/next-session-A5-roles-admin.md and CLAUDE.md. You are the PM. Start A5 (roles and admin users) on the branch
feature/roles-admin: draft specs/roles-admin.md from specs/_TEMPLATE.md, using the "Open questions" in the handoff doc
(with your recommendations), show me the spec and wait for my answers and approval. After approval, go straight to
contract and build without asking again (only stop for approval gates or real questions).
```

---

## Where the project stands

| Part | Status |
|---|---|
| A1 account settings (`specs/account-settings.md`) | DONE, on `main` |
| A2 sessions: remember me, 12h max, log out other devices (`specs/sessions.md`) | DONE, on `main` |
| A3 email verification and password reset (`specs/email-auth.md`) | DONE, on `main` |
| A4 Sign in with Google (`specs/google-sign-in.md`) | DONE, merged to `main` 2026-10-15 (73fa2ca) |
| **A5 roles and admin users** | **next — branch `feature/roles-admin` already created from `main`** |
| T1 tasks: priority, status, tags | later |
| T2 tasks: search, sort, pagination | later |
| T3 sharing / team lists | later |
| T4 due times, reminders, notifications, recurring tasks | later |
| T5 undo, trash, drag and drop, bulk actions | later |

Test baseline on `main` after A4: backend 520 tests, frontend 390 unit tests, lint and build clean, full e2e 165 passed
(plus the env-gated runs: `E2E_SHORT_SESSIONS`, `E2E_SHORT_LINKS`, `E2E_RATE_LIMIT`, `E2E_MAIL_DOWN`, `E2E_GOOGLE_OFF`).

## How the user wants us to work

- Follow `CLAUDE.md`: PM delegates to `backend-dev`, `frontend-dev`, `tester`; spec → contract (max 2 rounds) → build →
  test → max 3 fix rounds → final report in the CLAUDE.md format.
- **No pause before build:** once the spec is approved and the contract agreed, build straight away. Stop only for
  approval gates or real questions.
- **Git:** commit and push `feature/roles-admin` after each change. Merge to `main` and push only when the user says
  **"merge"**, and only with green tests. Never force-push.
- Explain options in plain words with a recommendation; the user answers short ("yes", "1. yes 2. …").
- When asking at an approval gate, give what / why / files / risks / reversible / alternative.

## What exists today that A5 builds on

- **No roles at all.** Every signed-in user is just `authenticated()` in `backend/.../security/SecurityConfig.java`.
  No `role` column; the principal's authorities are empty.
- **`users` table** (V1–V7): `id`, `email`, `password_hash` (NULL for Google-only accounts), `display_name`,
  `created_at`, `sessions_valid_after`, `email_verified_at`; Google links in `user_connections`.
  **Next migration is V8.**
- **`SessionUserRefreshFilter`** re-reads the user on every authenticated request (one primary-key lookup). It ends the
  session of a deleted user and refreshes the display name; it also compares `sessions_valid_after`. This is the natural
  place to make a role change or "disable account" take effect on the very next request. Note: it currently copies the
  *old* authorities (`authentication.getAuthorities()`), so it must be changed to load authorities from the fresh user.
- **`HashedRememberMeServices`** builds its own principal with authorities (line ~301); it must get the role too.
- **Ending a user's sessions** already exists: bump `sessions_valid_after` and delete their remember-me tokens
  (same as "log out all other devices"). Lock rules from the A2 deadlock fix apply: READ COMMITTED, lock the `users`
  row first (`SELECT … FOR UPDATE`), select then delete by primary key, at most one retry.
- **Audit log:** `security.audit` logger with `SecurityEvent` enum; never log secrets.
- **Rate limits:** per-user limiters in `security/ratelimit/`.
- **Sign-in paths that a "disabled" flag must block:** password login, remember-me cookie, Google sign-in callback,
  email-verification and password-reset links (decide whether reset still works), register (n/a).
- **Frontend:** `frontend/src/app/core/auth` (the `User` model now has `hasPassword` and `connections`; a `role`
  field would go here), `features/account`, `features/auth`, `features/tasks`. Header shows "Hi, <name>" → `/account`.

## Open questions for the spec (with recommendations)

1. **What can an admin do?** Recommend: on a new `/admin/users` page, list and search users (email, name, created,
   confirmed email, Google connected, role, disabled), **disable / re-enable** an account, **log a user out
   everywhere**, **delete** an account, and **make / remove admin**. Recommend admins **cannot see other users' tasks**
   (privacy) and cannot see or set passwords.
2. **Which roles?** Recommend just `USER` and `ADMIN`.
3. **How is the first admin created?** Recommend a setting `ADMIN_BOOTSTRAP_EMAIL`: on startup, if that account exists
   and there is no admin yet, it becomes admin (logged). No way to become admin from the UI on your own.
   Alternative: a documented one-line SQL command.
4. **Safety rules:** recommend an admin can't disable, delete or demote **themselves**, and the **last admin** can't be
   removed. Every admin action writes an audit event (who, what, which user).
5. **Disabled account:** recommend all sign-in paths refuse it, its sessions and remember-me logins end on the next
   request, and the login page shows "This account has been disabled" (only after a correct password / Google sign-in,
   so it can't be used to discover emails). Password reset emails are not sent for disabled accounts.
6. **Extra confirmation for dangerous admin actions?** Recommend a confirmation dialog (type the user's email for
   delete), no password re-entry (Google-only admins have no password).
7. **Should the user get an email when disabled, deleted or made admin?** Recommend out of scope for A5.
8. **Admin UI:** recommend an "Admin" link in the header shown only to admins; `/admin/**` routes guarded on the
   frontend, and every `/api/admin/**` endpoint returns 403 for non-admins on the backend (the backend is the real
   check). Paging for the list: simple page size 20.

## Approval gates expected in A5

- **Migration V8:** add `users.role` (e.g. `VARCHAR(16) NOT NULL DEFAULT 'USER'`, `CHECK role IN ('USER','ADMIN')`)
  and `users.disabled_at` (nullable). Backend proposes the exact SQL first.
- **Auth/security config:** role-based access rules (`/api/admin/**` → ADMIN), authorities on the session and
  remember-me principals, blocking disabled users on every sign-in path, new audit events, possibly a rate limit on
  admin actions.
- **API change:** `User` response gets `role` (additive, not a rename).
- **New dependency:** none expected.

## Open backlog (not part of A5 unless the user asks)

- Reset-password email should say "set" for accounts with no password (A4).
- Timeouts and a short backoff when Google's discovery URL can't be reached (A4).
- Share one error-response writer between security filters; define the 405 body for GET on the Google start URL
  (currently `MALFORMED_REQUEST`, not in the contract) (A4).
- Add the `oidc` and `mailpit` Docker images to Dependabot (A4).
- Prettier formatting pass; flaky `frontend/e2e/mysql-regression.spec.ts:78`; eslint error at
  `frontend/e2e/email-auth.spec.ts:182`.
- Delete tokens before account delete; an outbox for emails; scheduled cleanup of expired tokens (A2/A3).

## Dev setup reminder

`docker compose up -d db mail oidc`, then `cd backend && SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run` (applies
new migrations), then `cd frontend && npm start`. Emails: http://localhost:8025. Fake Google: http://localhost:8090.
