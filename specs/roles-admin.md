# Feature: Roles and admin users

**Status:** APPROVED 2026-10-15, in progress. **Depends on:** `specs/auth.md`, `specs/account-settings.md`, `specs/sessions.md`, `specs/email-auth.md`, `specs/google-sign-in.md` (all DONE). **Branch:** `feature/roles-admin`

## Why
Today every signed-in user is equal, so nobody can help a user who is locked out, stop an abusive account, or remove a dead one without touching the database by hand. Admin roles and a small admin page let trusted people do this safely, with every action logged.

## User story
As an admin, I want to see the list of users and disable, sign out or delete a normal user's account, so that I can keep the app healthy without running SQL.
As a super admin, I also want to make and remove admins and manage their accounts, so that only I decide who has admin powers.
As a normal user, I want my tasks to stay private, even from admins.

## Roles

| Can… | USER | ADMIN | SUPER_ADMIN |
|---|---|---|---|
| See `/admin/users` (all accounts, including admins and super admins) | – | ✓ | ✓ |
| Disable, enable, sign out everywhere, delete a **USER** | – | ✓ | ✓ |
| Do the same to an **ADMIN** | – | ✗ | ✓ |
| Make admin / remove admin (USER ↔ ADMIN) | – | ✗ | ✓ |
| Do anything to a **SUPER_ADMIN** (including another super admin) | – | ✗ | ✗ |
| Act on themselves from the admin page | – | ✗ | ✗ |
| Make someone SUPER_ADMIN | – | ✗ | ✗ (only the startup setting or SQL, AC4–AC5) |

## Acceptance criteria

### Roles
- [ ] AC1: Every account has exactly one role: `USER`, `ADMIN` or `SUPER_ADMIN`. New accounts (register, Google sign-up) are `USER`. Existing accounts become `USER` when this ships.
- [ ] AC2: The `User` response (`/api/auth/me` and wherever a `User` is returned) has a new field `role` (`USER` | `ADMIN` | `SUPER_ADMIN`). Nothing is renamed or removed.
- [ ] AC3: Nobody can raise their own role through the app. No API or page can set `SUPER_ADMIN` at all; only a super admin can set `ADMIN`.

### First super admin
- [ ] AC4: Given the setting `ADMIN_BOOTSTRAP_EMAIL` is set, when the backend starts and the account with that email exists (matched ignoring case and accents, as at login) and **no super admin exists yet**, then that account becomes `SUPER_ADMIN` and an `ADMIN_BOOTSTRAPPED` audit event is logged.
  - If a super admin already exists, the setting does nothing (it can't be used to take over later).
  - If the account doesn't exist, a warning is logged and the backend starts normally. It is checked again on the next start.
  - If the setting is empty, nothing happens.
- [ ] AC5: The README documents the setting and, as the emergency fallback, the one-line SQL to make an account super admin by hand (also the only way to add a second super admin or remove one).

### Admin page
- [ ] AC6: Given I am an admin or super admin, the header shows an "Admin" link next to "Hi, <name>". It leads to `/admin/users`. Normal users never see the link.
- [ ] AC7: Given I am a normal user (or not signed in), when I open `/admin/users`, then I am sent to `/tasks` (or `/login` when signed out). Every `/api/admin/**` endpoint returns 401 when signed out and 403 for a signed-in normal user, whatever the frontend does.
- [ ] AC8: `/admin/users` shows a table of all accounts, 20 per page, newest first, with: email, display name, created date, email confirmed (yes/no), Google connected (yes/no), role, status (Active / Disabled). My own row is marked "(you)".
- [ ] AC9: A search box filters by email or display name (contains, ignoring case and accents). Previous / Next buttons and "Page X of Y" move between pages. When nothing matches I see "No users found".
- [ ] AC10: Admins and super admins can't see other users' tasks, task counts, or passwords anywhere (UI or API). The admin API returns only the fields in AC8.

### Admin actions
Each row shows only the actions I'm allowed to do on that account (Roles table). Rows with no allowed action (myself, super admins, and admins when I'm an admin) show no actions menu. Every action asks for confirmation first, and the table refreshes afterwards with a success notice.

- [ ] AC11: **Disable / Enable.** Given an account is active, when I disable it and confirm, then it is marked Disabled. On the user's very next request all their sessions end and their remember-me logins stop working (see AC18). "Enable" makes the account usable again; they sign in normally (old sessions stay ended).
- [ ] AC12: **Sign out everywhere.** When I confirm, then all of that user's sessions and remember-me logins end (same as `specs/sessions.md` "log out all other devices", but including every device). The account stays active.
- [ ] AC13: **Make admin / Remove admin** (super admin only). When I confirm, then the user's role changes between `USER` and `ADMIN`. It takes effect on their next request: they gain or lose the Admin link and access to `/api/admin/**` without signing in again.
- [ ] AC14: **Delete.** The dialog explains that the account and all its tasks will be permanently deleted and asks me to type the user's email. The wrong text shows "Email doesn't match" and the button stays disabled. When it matches and I confirm, the account, its tasks and its Google connections are deleted, exactly as when a user deletes their own account (`specs/account-settings.md` AC11–AC13). The user's open sessions end on their next request.
- [ ] AC15: **Permission rules (the backend enforces them; hiding buttons is only convenience).**
  - Acting on **myself** → 409 `CANNOT_MODIFY_SELF`. (I use `/account` for my own sessions and account.)
  - An admin acting on an admin, or anyone acting on a super admin → 403 `INSUFFICIENT_ROLE`.
  - An admin trying to change a role → 403 `INSUFFICIENT_ROLE`.
  - Setting the role to `SUPER_ADMIN` through the API → 400 (not an allowed value).
  - If my own role was lowered in the meantime, the next action is checked against my new role.
  - Acting on a user who was deleted in the meantime shows "This user no longer exists" (404 `USER_NOT_FOUND`) and refreshes the table.
  - The dialog shows "You don't have permission to do this." for 403 and refreshes the table.
- [ ] AC16: Since super admins can't be changed in the app, the app can never lose its last super admin.
- [ ] AC17: **Audited:** every admin action writes one line to the `security.audit` log with when, the acting admin's id, the target user's id and the action: `ADMIN_USER_DISABLED`, `ADMIN_USER_ENABLED`, `ADMIN_USER_SESSIONS_REVOKED`, `ADMIN_ROLE_CHANGED` (from → to), `ADMIN_USER_DELETED`. Refused attempts log `ADMIN_ACCESS_DENIED` (who, what, reason). No secrets are logged.

### Disabled accounts
- [ ] AC18: Given my account is disabled, then:
  - Any page I have open stops working on the next request; I land on `/login` with "This account has been disabled."
  - Password login: with the **correct** password I see "This account has been disabled." With a wrong password I see the usual "Invalid email or password" (so it can't be used to discover accounts).
  - Remember-me cookie: it no longer signs me in.
  - Google sign-in: I land on `/login` with "This account has been disabled." Nothing is created or linked.
  - Forgot password: I see the normal message (`specs/email-auth.md` AC8), but no email is sent. A reset link sent before I was disabled shows "This link is invalid or has expired".
  - The email-confirmation link still confirms the email (it doesn't sign anyone in).
- [ ] AC19: Disabling keeps all the user's data (tasks, connections, role). Enabling brings everything back as it was.

### Security and states
- [ ] AC20: Admin endpoints require a valid CSRF token for changes (403 without it), like the rest of the API. Admin actions are rate limited per admin (default 60 per minute; over the limit 429 and "Too many attempts, please try again later").
- [ ] AC21: The admin page shows a loading state while the list loads, "Something went wrong, please try again" with a Retry button if it fails, and the empty state from AC9. Dialog buttons show a spinner while waiting and show errors inside the dialog.

## Out of scope
- Roles beyond `USER`, `ADMIN`, `SUPER_ADMIN`, or fine-grained permissions.
- Making, removing, disabling or deleting a super admin in the app (SQL only, AC5).
- Admins viewing, editing or exporting other users' tasks; impersonating ("log in as") a user.
- Admins setting or seeing passwords, changing a user's email or name.
- Emails to the user when they are disabled, deleted or their role changes.
- An audit-log viewer in the UI (events go to the log only).
- Creating users from the admin page.
- Bulk actions, sorting by column, CSV export.

## UI notes
- Header: "Admin" link for admins and super admins only, before "Hi, <name>".
- `/admin/users`: page title "Users", search box on top, table below, Previous / Next under the table. On phones the table becomes stacked cards.
- Role and status show as small badges ("Super admin", "Admin", "User"; "Active", "Disabled"). Actions sit in a "⋯" menu per row; destructive ones (Disable, Delete) in red.
- Confirm dialogs name the user's email, e.g. "Disable alice@example.com? They will be signed out everywhere and can't sign in until enabled."
- Notices: "User disabled", "User enabled", "User signed out everywhere", "Role changed", "User deleted".

## Constraints
- **Database (approval gate):** migration V8 adds `users.role` (`USER`/`ADMIN`/`SUPER_ADMIN`, default `USER`, checked) and `users.disabled_at` (nullable timestamp). Additive only.
- **Security config (approval gate):** `/api/admin/**` requires `ADMIN` or `SUPER_ADMIN`; per-target rules (Roles table) are checked in the service. The session principal, the remember-me principal and `SessionUserRefreshFilter` must load the role and disabled state from the fresh user on every request (today the filter copies the old authorities). Disabled users are refused on every sign-in path listed in AC18.
- **Ending a user's sessions** reuses the existing mechanism (bump `sessions_valid_after`, delete remember-me tokens) and its lock rules (READ COMMITTED, lock the `users` row first, select then delete by primary key, at most one retry). The permission check on the target runs inside the same locked transaction, so a role change racing with an action can't slip through.
- **Deleting a user** reuses the existing account-delete code path.
- **Existing behaviour stays:** login, register, Google sign-in, password reset, sessions, remember me and account settings work exactly as before for active accounts. The `User` response only gains `role`.
- **No new dependencies.**
- **Proposed API** (the backend finalizes it in `contracts/openapi.yaml`):
  - `GET /api/admin/users?q=&page=0&size=20` → page of `AdminUser` (AC8 fields + id)
  - `POST /api/admin/users/{id}/disable`, `POST /api/admin/users/{id}/enable`
  - `POST /api/admin/users/{id}/revoke-sessions`
  - `PUT /api/admin/users/{id}/role` `{ "role": "ADMIN" | "USER" }` (super admin only)
  - `DELETE /api/admin/users/{id}`
  - Error codes: `CANNOT_MODIFY_SELF` (409), `INSUFFICIENT_ROLE` (403), `USER_NOT_FOUND` (404), `ACCOUNT_DISABLED` (login / session).

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-15 | Admin powers: list/search, disable/enable, sign out everywhere, delete; no access to tasks or passwords | Privacy; enough to help or stop accounts | User |
| 2026-10-15 | Three roles: `USER`, `ADMIN`, `SUPER_ADMIN`. Only a super admin can make/remove admins and act on admin accounts | User asked for a super admin above admins | User |
| 2026-10-15 | First super admin via `ADMIN_BOOTSTRAP_EMAIL` at startup (only if none exists) + documented SQL fallback. Rejected: first sign-up wins (takeover risk), one-off CLI command, setup code page (more work) | Simple, safe, no takeover later | User |
| 2026-10-15 | Super admins can't be created, changed, disabled or deleted in the app, not even by another super admin (SQL only) | The app can never lose its last super admin | User |
| 2026-10-15 | Admins see all accounts in the list, including admins and super admins, but get no actions on them | Transparency without power | User |
| 2026-10-15 | No actions on yourself from the admin page; every admin action and refused attempt is audited in `security.audit` | Safety, traceability | User |
| 2026-10-15 | Disabled accounts blocked on every sign-in path; "disabled" message only after a correct password or Google sign-in; no reset emails; confirm-email link still works | No account discovery; disabled means disabled | User |
| 2026-10-15 | Confirm dialog for every action, type the email for delete, no password re-entry | Google-only admins have no password | User |
| 2026-10-15 | No emails to affected users in A5; 20 per page; frontend guard + backend 403 as the real check; 60 admin actions/min per admin | Scope; consistency with existing limiters | User |
