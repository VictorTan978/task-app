# Feature: Sessions (remember me, maximum session length, log out other devices)

**Status:** DRAFT 2026-10-13 (waiting for user approval). **Depends on:** `specs/auth.md`, `specs/account-settings.md` (both DONE). **Branch:** `feature/sessions`

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
- [ ] AC16: While "Log out all other devices" is in progress, its button is disabled and shows a loading indicator. If the server is unreachable, the card shows "Something went wrong, please try again".

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
1. **Lengths:** is a 12-hour maximum for normal sessions and 30 days for "Remember me" OK? (Draft: yes. Common choices are 8–24 hours and 14–30 days.)
2. **Password change** (AC11): should it log out all other devices? (Draft: yes. OWASP recommends it, and A1 deferred it to this part.)
3. **Log out other devices** (AC10): should it ask for confirmation (draft: yes, a simple dialog) or for your password too? (Draft: no password. It only removes access, so there is nothing to protect.)
4. **Maximum length with "Remember me":** should the 12-hour maximum also apply to remember-me sessions? (Draft: no. The 30-day remember-me limit takes over, and each automatic sign-in starts a new session.)

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
