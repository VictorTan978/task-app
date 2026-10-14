# Feature: Email verification and password reset

**Status:** APPROVED 2026-10-14, in build. **Depends on:** `specs/auth.md`, `specs/account-settings.md`, `specs/sessions.md` (all DONE). **Branch:** `feature/email-auth`

## Why
Today the app never checks that an email address is real, and a user who forgets their password has no way back into their account. Both need the app to send email.

## User story
As a user, I want to confirm my email address and reset a forgotten password by email, so that my account is tied to an address I control and I can always get back in.

## Acceptance criteria

### Email verification
- [ ] AC1: Given I register a new account, then I am signed in as today (`specs/auth.md` AC1). I also receive an email "Confirm your email for Task App" with a "Confirm email" link, valid for 24 hours.
- [ ] AC2: Given my email is not confirmed, when I use the app, then a banner at the top of every signed-in page says "Please confirm your email address. We sent a link to <email>." with a "Resend email" button. The rest of the app works normally.
- [ ] AC3: Given I open the confirmation link (signed in or not, in any browser), then I see "Your email is confirmed" with a button to continue, and the banner no longer shows.
- [ ] AC4: Given the link has expired, was already used, or was replaced by a newer one, when I open it, then I see "This link is invalid or has expired". If I am signed in and still unconfirmed, I can resend it from there.
- [ ] AC5: Given I click "Resend email", then a new link is sent, older unused links stop working, and I see "Email sent". Resending is limited (default: 3 per hour per account). Over the limit I see "Too many emails, please try again later".
- [ ] AC6: Accounts created before this feature count as not confirmed. They see the banner (AC2) and can confirm the same way.

### Forgot / reset password
- [ ] AC7: Given I am on `/login`, there is a "Forgot password?" link to `/forgot-password`, a page with an email field and a "Send reset link" button.
- [ ] AC8: Given I submit any well-formed email on `/forgot-password`, then I always see the same message: "If an account exists for that email, we've sent a link to reset your password." Only a registered email (matched ignoring case and accents, as at login) actually gets an email "Reset your Task App password", with a link valid for 1 hour. The response doesn't reveal whether the account exists, in its text, status code or timing.
- [ ] AC9: Given I open a valid reset link, then I see `/reset-password` with "New password" and "Confirm new password" fields (with show/hide, same rules as registration: 8–72 characters, must match). When I submit, then my password is changed, I land on `/login` with "Your password has been reset. Please sign in.", and the new password works.
- [ ] AC10: Given I reset my password, then all my existing sessions and remember-me logins on every device are ended (as in `specs/sessions.md` AC10), and my email counts as confirmed (I proved I own it).
- [ ] AC11: Given a reset link has expired, was already used, or was replaced by a newer request, when I open or submit it, then I see "This link is invalid or has expired" with a link back to `/forgot-password`. Requesting a new link makes all older reset links stop working.
- [ ] AC12: Reset requests are rate limited without revealing whether the account exists. Defaults: 3 emails per hour per email address and 10 requests per hour per IP. Over the IP limit I see "Too many attempts, please try again later". Over the per-email limit, the page shows the normal message but no email is sent.

### Email delivery
- [ ] AC13: In dev, all emails go to Mailpit (`docker compose up -d mail`) and can be read at http://localhost:8025. Nothing is sent to real addresses.
- [ ] AC14: Emails come from a configurable sender (default "Task App <no-reply@taskapp.local>"). They have a plain-text and a simple HTML version, and their links point to the frontend URL from settings (`APP_BASE_URL`, default http://localhost:4200).
- [ ] AC15: If the email can't be sent (mail server down), registering still works, the error is logged, and the user can use "Resend email" later. On `/forgot-password` the user still sees the normal message (AC8); the failure appears only in the backend log.

### Security
- [ ] AC16: Link tokens are random (at least 256 bits), single-use, and only their hash is stored. No token or link appears in any log line or API response.
- [ ] AC17: Security events go to the `security.audit` log: `EMAIL_VERIFICATION_SENT`, `EMAIL_VERIFIED`, `PASSWORD_RESET_REQUESTED` (whether or not the account exists, logged with the email the user typed), `PASSWORD_RESET_COMPLETED`, `EMAIL_TOKEN_REJECTED` (reason) and `EMAIL_SEND_FAILED`.
- [ ] AC18: The new public endpoints (forgot, reset, verify) require a CSRF token like login does. Resend requires being signed in.

### States
- [ ] AC19: Every new page and button shows a loading state while waiting. If the server is unreachable it shows "Something went wrong, please try again". The confirm and reset pages show a loading state while the link is checked.

## Out of scope
- Changing the email address on `/account` (it needs confirming the new address; it could be a small follow-up now that email works).
- Blocking unconfirmed accounts from using the app, or deleting unconfirmed accounts after some time.
- Email alerts (new sign-in, password changed, suspicious cookie). This feature only sends the confirmation and reset emails.
- Choosing and setting up the production email provider and DNS (see "Going live checklist").
- Branded or localized email templates.

## UI notes
- **Banner (AC2):** a slim bar under the header on every signed-in page, with the "Resend email" button. Not shown when the email is confirmed.
- **New public pages,** each a centered card in the same style as `/login`:
  - `/verify-email?token=…` — checks the link and shows the result.
  - `/forgot-password` — email field and button.
  - `/reset-password?token=…` — new password and confirm, using the shared password field.
- **`/login`:** a "Forgot password?" link under the password field. A notice after a reset (AC9).
- `/forgot-password`, `/reset-password` and `/verify-email` work whether you are signed in or not.

## Constraints
- **Sending email:** Spring's mail support (`spring-boot-starter-mail`, a new dependency) over SMTP. The provider is chosen only by settings: `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, `APP_BASE_URL`. Dev defaults point at Mailpit (localhost:1025).
- **Mailpit:** a new `mail` service in `docker-compose.yml` (image `axllent/mailpit`, ports bound to 127.0.0.1 only: 1025 SMTP, 8025 web UI).
- **Sending must not slow down or break the request:** a failed send never fails register, and `/forgot-password` takes about the same time whether or not the account exists.
- **Tokens:** a new table owned by the user (deleted with the user): purpose (verify email or reset password), token hash, expiry, used time, created time. A new nullable `users.email_verified_at` column.
- **A password reset reuses the sessions code** from `specs/sessions.md` (`sessions_valid_after`, delete remember-me tokens) to end every session.
- **Unchanged behaviour:** register, login, logout, account settings, sessions and tasks. `GET /api/auth/me` gains an `emailVerified` field (added, not renamed).
- **Proposed API** (the backend finalizes it in `contracts/openapi.yaml`):
  - `POST /api/auth/verify-email` `{token}` → 204; 400 `TOKEN_INVALID`
  - `POST /api/account/resend-verification` → 204; 429
  - `POST /api/auth/forgot-password` `{email}` → 202 (always); 429 (per-IP limit only)
  - `POST /api/auth/reset-password` `{token, newPassword}` → 204; 400 `TOKEN_INVALID` / `VALIDATION_FAILED`
  - `User` gets `emailVerified: boolean`.
- **Backend tests** need a mail server. Use Mailpit through the existing Testcontainers setup if possible; any new test dependency (e.g. GreenMail) needs approval.

## Approvals needed before build
- **New dependency:** `spring-boot-starter-mail` (official Spring Boot starter, open source, free).
- **Database schema change:** migration `V6`: a new email token table (FK to `users`, cascade delete) and the nullable `users.email_verified_at` column. The backend will propose the exact SQL first.
- **Authentication and security config:** three new public endpoints, a new rate limit, ending sessions on password reset, and the new `emailVerified` field on the user.
- **Infrastructure:** a Mailpit service in `docker-compose.yml` (dev only, localhost only).

## Going live checklist (not part of this build)
1. Pick an email provider with SMTP (for example Brevo, Mailgun, Postmark, Amazon SES) and create an account.
2. Verify the sending domain in the provider's dashboard (SPF and DKIM DNS records) so emails don't land in spam.
3. Set `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` and `APP_BASE_URL` on the server.

## Open questions
None. All answered 2026-10-14 (see Decisions log).

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-14 | Email via SMTP with Spring's mail support; Mailpit for dev; the production provider is chosen later by settings only | Free, fully testable locally, no vendor lock-in | User (option 1) |
| 2026-10-14 | Spec approved, including: new dependency `spring-boot-starter-mail`; migration V6 (email token table + nullable `users.email_verified_at`); the auth/security changes (3 public endpoints, new rate limit, ending sessions on reset, `emailVerified` on User); the Mailpit service in docker-compose (dev, localhost only). Any other dependency (e.g. a test mail library) still needs a separate approval | Approval gates | User |
| 2026-10-14 | Unconfirmed accounts are fully usable, with a banner only | Open question 1 | User |
| 2026-10-14 | Link lifetimes: confirmation 24h, reset 1h | Open question 2 | User |
| 2026-10-14 | After a reset the user signs in again on `/login` (no automatic sign-in) | Open question 3 | User |
| 2026-10-14 | Accounts created before this feature are unconfirmed and see the banner | Open question 4 | User |
| 2026-10-14 | Changing the email address is a separate follow-up, not part of A3 | Open question 5 | User |

