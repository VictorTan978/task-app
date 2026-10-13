# Feature: Account settings

**Status:** APPROVED 2026-10-13, in build. **Depends on:** `specs/auth.md`, `specs/security-hardening.md` (both DONE). **Branch:** `feature/account-settings`

## Why
Signed-in users can't change anything about their account. Today they can't fix a display name typo, change a password they think has leaked, or leave the app and take their data with them.

## User story
As a signed-in user, I want to change my display name and password, and delete my account, so that I control my own account and data.

## Acceptance criteria

### Account page
- [ ] AC1: Given I am signed in, when I click my name ("Hi, <display name>") in the header, then I land on `/account`. It shows my email (read-only) and three sections: "Display name", "Change password" and "Delete account".
- [ ] AC2: Given I am not signed in, when I visit `/account`, then I am sent to `/login`.

### Change display name
- [ ] AC3: Given I am on `/account`, when I enter a new display name (1–50 characters after trimming) and click "Save", then I see "Display name updated" and the header shows "Hi, <new name>" without a page reload. After a refresh the new name is still shown.
- [ ] AC4: Given another user has the display name "José", when I try to save "jose", "JOSE" or " José " (matching ignores letter case and accents, as at registration), then the name is not changed and I see "This display name is already taken" under the field.
- [ ] AC5: Given my display name is "Bob", when I save "bob" (only the letter case or accents differ from my own name), then it is saved as "bob". My own name never counts as taken.
- [ ] AC6: Given I am on `/account`, when I save an empty or whitespace-only display name, or one longer than 50 characters, then nothing changes and an error appears under the field.

### Change password
- [ ] AC7: Given I am on `/account`, when I enter my current password and a new password of 8–72 characters and click "Change password", then I see "Password changed", the fields are cleared, and I stay signed in. After logging out, the old password fails ("Invalid email or password") and the new one works.
- [ ] AC8: Given I am on `/account`, when I enter a wrong current password, then the password doesn't change and I see "Current password is incorrect" under the current password field.
- [ ] AC9: Given I am on `/account`, when the new password is shorter than 8 or longer than 72 characters, then nothing changes and an error appears under the new password field.

### Delete account
- [ ] AC10: Given I am on `/account`, when I click "Delete account", then a confirmation dialog explains that my account and all my tasks will be permanently deleted and asks for my password. "Cancel" closes it with no changes.
- [ ] AC11: Given the dialog is open, when I enter my correct password and confirm, then my account and all my tasks are deleted, I am signed out and land on `/login` with "Your account has been deleted". Signing in with the old email and password then fails with "Invalid email or password", and the email and display name can be used to register again.
- [ ] AC12: Given the dialog is open, when I enter a wrong password and confirm, then nothing is deleted and I see "Password is incorrect" in the dialog.
- [ ] AC13: Given user A deletes their account, then user B's account and tasks are unaffected.

### Security
- [ ] AC14: Changing the password, changing the display name and deleting the account each write a security event to the `security.audit` log (`PASSWORD_CHANGED`, `DISPLAY_NAME_CHANGED`, `ACCOUNT_DELETED`; a wrong current password logs a failure event). No password ever appears in a log or a response.
- [ ] AC15: Too many wrong current-password attempts on change password or delete account (default: 5 failures in 15 minutes per user) are rejected with 429 and "Too many attempts, please try again later", like the login rate limit.
- [ ] AC16: All the new endpoints return 401 when not signed in, and 403 without a valid CSRF token.

### States
- [ ] AC17: While a save, password change or delete is in progress, that section's button is disabled and shows a loading indicator. If the server is unreachable, an error banner appears: "Something went wrong, please try again".

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
