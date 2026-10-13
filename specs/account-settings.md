# Feature: Account settings

**Status:** DONE 2026-10-13 (AC1–AC22). **Depends on:** `specs/auth.md`, `specs/security-hardening.md` (both DONE). **Branch:** `feature/account-settings`

## Why
Signed-in users can't change anything about their account. Today they can't fix a display name typo, change a password they think has leaked, or leave the app and take their data with them.

## User story
As a signed-in user, I want to change my display name and password, and delete my account, so that I control my own account and data.

## Acceptance criteria

### Account page
- [x] AC1: Given I am signed in, when I click my name ("Hi, <display name>") in the header, then I land on `/account`. It shows my email (read-only) and three sections: "Display name", "Change password" and "Delete account".
- [x] AC2: Given I am not signed in, when I visit `/account`, then I am sent to `/login`.

### Change display name
- [x] AC3: Given I am on `/account`, when I enter a new display name (1–50 characters after trimming) and click "Save", then I see "Display name updated" and the header shows "Hi, <new name>" without a page reload. After a refresh the new name is still shown.
- [x] AC4: Given another user has the display name "José", when I try to save "jose", "JOSE" or " José " (matching ignores letter case and accents, as at registration), then the name is not changed and I see "This display name is already taken" under the field.
- [x] AC5: Given my display name is "Bob", when I save "bob" (only the letter case or accents differ from my own name), then it is saved as "bob". My own name never counts as taken.
- [x] AC6: Given I am on `/account`, when I save an empty or whitespace-only display name, or one longer than 50 characters, then nothing changes and an error appears under the field.

### Change password
- [x] AC7: Given I am on `/account`, when I enter my current password and a new password of 8–72 characters and click "Change password", then I see "Password changed", the fields are cleared, and I stay signed in. After logging out, the old password fails ("Invalid email or password") and the new one works.
- [x] AC8: Given I am on `/account`, when I enter a wrong current password, then the password doesn't change and I see "Current password is incorrect" under the current password field.
- [x] AC9: Given I am on `/account`, when the new password is shorter than 8 or longer than 72 characters, then nothing changes and an error appears under the new password field.

### Delete account
- [x] AC10: Given I am on `/account`, when I click "Delete account", then a confirmation dialog explains that my account and all my tasks will be permanently deleted and asks for my password. "Cancel" closes it with no changes.
- [x] AC11: Given the dialog is open, when I enter my correct password and confirm, then my account and all my tasks are deleted, I am signed out and land on `/login` with "Your account has been deleted". Signing in with the old email and password then fails with "Invalid email or password", and the email and display name can be used to register again.
- [x] AC12: Given the dialog is open, when I enter a wrong password and confirm, then nothing is deleted and I see "Password is incorrect" in the dialog.
- [x] AC13: Given user A deletes their account, then user B's account and tasks are unaffected.

### Security
- [x] AC14: Changing the password, changing the display name and deleting the account each write a security event to the `security.audit` log (`PASSWORD_CHANGED`, `DISPLAY_NAME_CHANGED`, `ACCOUNT_DELETED`; a wrong current password logs a failure event). No password ever appears in a log or a response.
- [x] AC15: Too many wrong current-password attempts on change password or delete account (default: 5 failures in 15 minutes per user) are rejected with 429 and "Too many attempts, please try again later", like the login rate limit.
- [x] AC16: All the new endpoints return 401 when not signed in, and 403 without a valid CSRF token.

### States
- [x] AC17: While a save, password change or delete is in progress, that section's button is disabled and shows a loading indicator. If the server is unreachable, an error banner appears: "Something went wrong, please try again".

### Password fields (added 2026-10-13 at the user's request)
- [x] AC18: Every password field (login, register, current and new password on `/account`, the delete dialog, and the new confirm fields) has a show/hide button inside the field. Clicking it shows the typed text, and clicking again hides it. The button's label switches between "Show password" and "Hide password" (`aria-label`, `aria-pressed`), it is reachable with Tab, and it doesn't submit the form. Each field toggles on its own. Fields start hidden on every page load and after a successful submit.
- [x] AC19: Given I am on `/register`, there is a "Confirm password" field below "Password". When the two don't match and I submit, then nothing is sent and "Passwords do not match" appears under "Confirm password". When "Confirm password" is empty, "Confirm your password" appears instead.
- [x] AC20: Given I am on `/account`, the "Change password" card has a "Confirm new password" field below "New password". The same rules as AC19 apply ("Passwords do not match" / "Confirm your new password"), and nothing is sent.
- [x] AC21: When the passwords match, register and change password work exactly as before (AC1 in `specs/auth.md`, AC7 here). The confirm field is never sent to the API, and after "Password changed" it is cleared along with the other fields.
- [x] AC22: Login and the delete dialog get only the show/hide button and no confirm field. Login behaviour is otherwise unchanged.

## Out of scope
- Changing the email address (needs email verification, which comes in a later part)
- Signing out other devices after a password change (comes in the "sessions" part)
- Soft delete, a grace period or account restore. Deletion is immediate and permanent.
- Exporting my data before deleting
- Profile pictures or other profile fields

## UI notes
- New route `/account` inside the signed-in shell, page title "Account · Task App".
- In the header, "Hi, <display name>" becomes a link to `/account`.
- One centered column with three card sections in the same plain SCSS style as the login card. "Delete account" is last, styled as a danger zone with a red button.
- The confirmation dialog reuses the look of the existing task delete dialog.

## Constraints
- No database migration: tasks already have `ON DELETE CASCADE` on `owner_id`.
- No new dependencies.
- Same rules as registration: display name 1–50 characters after trimming, unique ignoring case and accents (`uk_users_display_name`); password 8–72 characters and at most 72 UTF-8 bytes; BCrypt.
- Errors are RFC 7807 ProblemDetail with `code`, as in the existing API.
- Proposed API (the backend finalizes it in `contracts/openapi.yaml`):
  - `PUT /api/account/display-name` `{displayName}` → 200 User; 400 VALIDATION_FAILED; 409 DISPLAY_NAME_TAKEN
  - `PUT /api/account/password` `{currentPassword, newPassword}` → 204; 400 VALIDATION_FAILED / WRONG_PASSWORD; 429 RATE_LIMITED
  - `POST /api/account/delete` `{password}` → 204 and the session is ended; 400 WRONG_PASSWORD; 429 RATE_LIMITED
- Existing login, register, logout and tasks behaviour must not change.
- After a display name change, the session's signed-in user is updated too, so later requests and audit logs see the new name.
- If the account is deleted while signed in on other tabs or devices, those sessions get 401 on their next API call. They follow the existing "session expired" flow and never cause a 500 or create orphan data.
- The AC15 counter is shared by change password and delete account and keyed by user id, so 5 wrong attempts across both endpoints trigger the limit.

## Approvals needed before build
- **Touches authentication and security config:** new authenticated endpoints that check passwords, a session ended on account delete, new security audit events, and a new rate limit for current-password checks.
- No migration and no new dependencies.

## Open questions
None. All answered 2026-10-13 (see Decisions log).

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-13 | Remaining auth/account work is split into parts, each on its own branch: account settings → sessions → email (verification and reset) → Google sign-in → roles/admin. The tasks parts follow. | Smallest self-contained part first; the email and Google parts need outside setup | User (order); PM (split) |
| 2026-10-13 | Spec approved, including the approval gate for auth/security changes (new password-checking endpoints, session end on delete, audit events, new rate limit) | Approval gate | User |
| 2026-10-13 | After a password change the current session stays signed in | Open question 1 | User |
| 2026-10-13 | Deleting the account requires the current password | Open question 2 | User |
| 2026-10-13 | Wrong current password: 5 failures per 15 minutes per user, then 429 | Open question 3; matches the login limit | User |
| 2026-10-13 | Git flow: commit and push the feature branch after each change; merge and push `main` only when the user asks and tests pass; never force-push | Settings and CLAUDE.md updated | User |
| 2026-10-13 | Other sessions of a deleted account get 401 on their next call; the session principal is refreshed after a display name change; one wrong-password counter per user covers both endpoints | Gaps found in the Phase 0 spec check | PM |
| 2026-10-13 | Contract agreed in round 1 (frontend ACCEPT), `contracts/openapi.yaml` 0.5.0: `PUT /api/account/display-name` (200 User; 400 VALIDATION_FAILED; 409 DISPLAY_NAME_TAKEN), `PUT /api/account/password` (204; 400 VALIDATION_FAILED/WRONG_PASSWORD on `currentPassword`; 429), `POST /api/account/delete` (204, expires JSESSIONID and sets a new XSRF-TOKEN; 400 WRONG_PASSWORD on `password`; 429). Wrong password is 400, never 401. `POST .../delete` instead of `DELETE` because the request has a body | See contract | PM |
| 2026-10-13 | The account page shows its own AC15 rate-limit text; the login text is unchanged. AccountService does no CSRF retry (same as TasksService) | Keep existing behaviour unchanged | PM |
| 2026-10-13 | Password-check rate limit settings: `RATE_LIMIT_PASSWORD_CHECK_FAILURES_PER_USER` (5) and `RATE_LIMIT_PASSWORD_CHECK_FAILURE_WINDOW` (15m); validation failures don't count; a correct password resets the counter | Backend proposal | PM |
| 2026-10-13 | Approved: a security filter that looks up the signed-in user on each request; a deleted user's session is ended (then 401), and the display name is refreshed. One PK lookup per authenticated request | Spec constraint (other sessions of a deleted account get 401); security config gate | User |
| 2026-10-13 | Tester verdict PASS: AC1–AC17 and Constraints. Backend 248 tests, frontend 191 unit tests, lint, build; e2e `account-settings.spec.ts` 24/24; full e2e suite 90 passed, plus the 3 env-gated specs run separately and passed. No fix rounds needed | Definition of Done | PM |
| 2026-10-13 | Added AC18–AC22: show/hide button on every password field; confirm password on register and change password, checked in the browser only (no API or contract change). Built on `feature/account-settings` because A1 isn't merged yet | User request; the user's explicit request counts as approval for this UI-only change to the login/register pages | User |
| 2026-10-13 | Tester verdict PASS for AC18–AC22, no regressions: backend 248, unit 207, lint, build; full e2e 103 passed (plus the env-gated rate-limit 5/5 and logging specs). Existing e2e specs updated to fill the confirm fields; `password-fields.spec.ts` added. Known flaky test: `mysql-regression.spec.ts:78` (reads a PUT response body after navigation, a test timing issue unrelated to this change) | Definition of Done | PM |

