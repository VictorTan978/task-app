# Feature: Sessions (remember me, maximum session length, log out other devices)

**Status:** APPROVED 2026-10-13, in build. **Depends on:** `specs/auth.md`, `specs/account-settings.md` (both DONE). **Branch:** `feature/sessions`

## Why
Today every sign-in is a 30-minute idle session that is gone when the browser closes, so people who use the app daily sign in again and again. Users can't end a session they left signed in on another computer, and a session kept busy never ends at all. A changed password also leaves old sessions on other devices signed in (A1 deferred this to this part).

## User story
As a signed-in user, I want to stay signed in on my own device, have sessions end after a fixed time, and sign out my other devices, so that the app is convenient on my devices and safe if I forget to log out somewhere else.

## Acceptance criteria

### Remember me
- [ ] AC1: Given I am on `/login`, there is a "Remember me" checkbox below the password field. It is unchecked by default.
- [ ] AC2: Given I sign in with "Remember me" checked, when I close the browser (or my 30-minute idle session expires) and open the app again within 30 days, then I am signed in automatically and land on `/tasks`. I don't see the login page or the "session expired" message.
- [ ] AC3: Given I sign in without "Remember me", when I close the browser and open the app again, then I land on `/login` (today's behaviour is unchanged).
- [ ] AC4: Given I signed in with "Remember me" more than 30 days ago, when I open the app, then I land on `/login`. The remember-me period is counted from that sign-in and isn't extended by use.
- [ ] AC5: Given I signed in with "Remember me", when I click "Log out", then I land on `/login`. Reopening the browser does not sign me back in on that device.
- [ ] AC6: Given someone copies an old remember-me cookie that has already been used (stolen cookie reuse), when it is used, then it is rejected, all of that user's remember-me logins are ended, and a security event is logged.

### Maximum session length
- [ ] AC7: Given I signed in without "Remember me", when 12 hours have passed since I signed in, then my next API call is treated as signed out, even if I was active the whole time. The app shows the existing "Your session expired, please sign in again" flow.
- [ ] AC8: The 30-minute idle timeout stays as it is. Both limits are settings: idle stays at 30m; the maximum session length defaults to 12h (`SESSION_MAX_LENGTH`); the remember-me length defaults to 30d (`REMEMBER_ME_LENGTH`).

### Log out other devices
- [ ] AC9: Given I am on `/account`, there is a "Sessions" card with a "Log out all other devices" button and a short explanation.
- [ ] AC10: Given I am signed in on devices A and B (with or without "Remember me"), when I click "Log out all other devices" on A and confirm, then I see "You've been logged out on all other devices" and I stay signed in on A. On B, the next API call follows the "session expired" flow, and reopening B's browser does not sign it back in.
- [ ] AC11: Given I change my password on device A (A1, AC7), then all my other sessions and remember-me logins are ended, the same as AC10, and A stays signed in. The session on A gets a new session id.

### Security
- [ ] AC12: The remember-me cookie is HttpOnly, SameSite=Lax and Secure outside dev. Only a hash of its secret is stored in the database, and each use replaces the token (rotation).
- [ ] AC13: Deleting the account (A1) also removes all its remember-me logins.
- [ ] AC14: Security events go to the `security.audit` log: `REMEMBER_ME_LOGIN`, `REMEMBER_ME_REJECTED` (expired, unknown or reused cookie; reuse at WARN), `SESSION_MAX_LENGTH_REACHED`, and `OTHER_SESSIONS_LOGGED_OUT` (with the trigger: the button or a password change). No token or cookie value is ever logged.
- [ ] AC15: The new endpoint returns 401 when not signed in and 403 without a valid CSRF token.

### States
- [ ] AC16: While "Log out all other devices" is in progress, its button is disabled and shows a loading indicator. If the server is unreachable, the confirmation dialog shows "Something went wrong, please try again" and can be retried.

## Out of scope
- A list of active sessions or devices (with name, location or last seen) and logging out one chosen device. That needs a session store with device data; it could come later.
- Sharing sessions across several app instances (Spring Session / Redis). Normal sessions stay in memory; remember-me logins are in the database, so they survive a restart.
- Email alerts on a new sign-in or on stolen-cookie detection (needs email, part A3).
- "Log out everywhere including this device" as a separate button. Log out on this device, plus this button, covers it.

## UI notes
- `/login`: a "Remember me" checkbox below the password field, above "Sign in".
- `/account`: a new "Sessions" card between "Change password" and "Delete account", with:
  - the text "Signed in somewhere else, like a shared computer? Log out everywhere except this device."
  - a "Log out all other devices" button
  - a small confirmation dialog (same style as the delete dialog, without a password field)

## Constraints
- **Remember me:** uses Spring Security's built-in remember-me support (already part of `spring-security-web`) with persistent, rotating tokens. No new dependency is expected.
- **Database tokens:** stored in a new table owned by the user, deleted when the user is deleted (`ON DELETE CASCADE`). They store only the token hash, the expiry and the creation time. A user can have several (one per device).
- **Log out other devices:** a new column on `users` (for example `sessions_valid_after`) holds the time all older sessions became invalid. The existing per-request account check compares it with the session's sign-in time, so this works without a session registry and costs no extra query.
- **Maximum session length:** checked in the same filter using the sign-in time stored in the session. No schema change for this part.
- **Unchanged behaviour:** login without "Remember me", register, logout, tasks, the A1 endpoints, and the existing rate limits all stay as they are. A wrong password still answers 400, not 401.
- **Remember-me sign-ins:** when the cookie signs someone in, the app goes through the same session setup as a normal login (new session id, CSRF token rotation, the AC7 clock starts again).
- **Proposed API** (the backend finalizes it in `contracts/openapi.yaml`):
  - `POST /api/auth/login`: gets an optional `rememberMe` (boolean, default false).
  - `POST /api/account/logout-other-sessions`: returns 204.
  - `GET /api/auth/me`: can sign in from the remember-me cookie.

## Approvals needed before build
- **Database schema change:** migration `V5`. It adds a remember-me token table (FK to `users`, cascade delete) and adds a column to `users` for "sessions valid after". The backend will propose the exact SQL first.
- **Authentication and security config:** the remember-me filter and cookie, the session length check in the filter, ending other sessions, a session id change after a password change, and new audit events.
- **New dependency:** none expected. If the backend finds it needs one, I'll ask separately.

## Open questions
None. All answered 2026-10-13 (see Decisions log).

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-13 | Spec approved, including the approval gates for migration V5 (remember-me token table plus a "sessions valid after" column on `users`) and the auth/security config changes. Any new dependency still needs a separate approval | Approval gates | User |
| 2026-10-13 | Normal sessions: 12h maximum from sign-in; remember me: 30 days from sign-in, not extended by use | Open question 1 | User |
| 2026-10-13 | A password change logs out all other devices and renews this session's id | Open question 2 | User |
| 2026-10-13 | "Log out all other devices" needs a simple confirmation, no password | Open question 3 | User |
| 2026-10-13 | The 12h maximum applies only to normal sessions; remember-me logins are limited by their 30 days, and each automatic sign-in starts a new session | Open question 4 | User |
| 2026-10-13 | Remember me is built on Spring's `AbstractRememberMeServices` with our own hashed token storage, not `PersistentTokenBasedRememberMeServices`, which stores tokens in plain text, extends the expiry on use, turns cookie theft into a 500, and logs out every device on logout. No new dependency | Backend pushback; needed for AC4, AC5, AC6 and AC12 | PM |
| 2026-10-13 | V5 migration SQL accepted as within the approved scope (one token table + `users.sessions_valid_after`). The table also has `series_hash` (lookup key, hashed), `previous_token_hash` and `rotated_at` (60s grace for parallel requests after rotation, so they aren't flagged as theft) | Same table the user approved; the extra columns are hashes or timestamps needed for correctness | PM (within the user's approval) |
| 2026-10-13 | Stolen-cookie detection (AC6) ends ALL of that user's sessions, not only remember-me logins (stricter than the spec) | A thief may already hold a session made from the stolen cookie | PM |
| 2026-10-13 | A remember-me session ends at its token's expiry (caps busy sessions); log out others and password change rotate the session id but not the XSRF token; remember-me cookie `Path=/api`; register expires any remember-me cookie the request carries; log out others is not rate-limited | Backend design, contract 0.6.0 | PM |
| 2026-10-13 | Contract agreed in round 1 (frontend ACCEPT), `contracts/openapi.yaml` 0.6.0. `POST /api/auth/login` gets optional `rememberMe`; new `POST /api/account/logout-other-sessions` (204/401/403). Remember-me sign-in can happen on any `/api/**` request; no new error codes. Frontend: a page-level busy lock on `/account` while password change or log out others is in flight; no interceptor, initializer or guard change | See contract | PM |
| 2026-10-14 | Backend build notes: log out others and password change move this session's `validFrom` before the DB update (rolled back if it fails), so other tabs of this browser stay signed in; automatic sign-in issues exactly one XSRF-TOKEN; the token table uses plain JDBC (BINARY(32) key, conditional UPDATE for rotation) | Backend BUILD | PM |
| 2026-10-14 | AC16 wording: the error shows in the confirmation dialog (where the request is made), not on the card | Tester note; matches the build | PM |
| 2026-10-14 | Test round 1: AC1–AC16 PASS, but bug B1: parallel remember-me logins can deadlock on `remember_me_tokens` (gap locks from the expired-row DELETE before the INSERT), giving 500. Sent to backend (fix round 1 of 3) | Contract allows only 200/400/401/403/429 on login | PM |
| 2026-10-14 | B1 fix: (1) multi-row token deletes now SELECT and then delete by primary key, so empty ranges take no gap locks; (2) one lock order everywhere: the `users` row (`SELECT … FOR UPDATE`) first, then tokens by primary key; (3) READ COMMITTED for token and session-ending transactions; (4) a single plain-code retry on lock conflict as a safety net (it never fired in tests). New `RememberMeConcurrencyIntegrationTest`: 9/15 failed before the fix, 15/15 passed (x3) after | Root cause, not just a retry; no schema or dependency change | PM |
| 2026-10-14 | Production MySQL must keep binlog_format ROW (the 8.4 default), because READ COMMITTED doesn't work with STATEMENT | Backend note | PM |

