# Feature: Sign in with Google

**Status:** APPROVED 2026-10-15, in build. **Depends on:** `specs/auth.md`, `specs/account-settings.md`, `specs/sessions.md`, `specs/email-auth.md` (all DONE). **Branch:** `feature/google-sign-in`

## Why
Many people would rather not create another password. Signing in with a Google account is faster, and Google has already checked that the email address is theirs.

## User story
As a visitor or user, I want to sign in with my Google account, so that I don't need a separate password for this app. I also want to connect or disconnect Google on my existing account.

## Acceptance criteria

### Sign in / sign up with Google
- [ ] AC1: Given Google sign-in is configured, `/login` and `/register` show a "Continue with Google" button (Google's standard look) below the form, separated by "or". When it isn't configured, the button doesn't show and nothing else changes.
- [ ] AC2: Given I click "Continue with Google" and approve on Google's page, when no account uses my Google account or my Google email yet, then a new account is created and I land on `/tasks` signed in:
  - The email is my Google email, and it counts as confirmed (no banner).
  - The display name is my Google name (see AC4).
  - The account has no password.
- [ ] AC3: Given I already signed up with Google, when I click "Continue with Google" again, then I am signed in to the same account and land on `/tasks`.
- [ ] AC4: The display name follows the existing rules (1–50 characters after trimming, unique ignoring case and accents):
  - A Google name longer than 50 characters is shortened to 50.
  - A taken name gets a number added ("José 2", "José 3", …).
  - An empty Google name uses the part of the email before "@".
  - I can change the name later on `/account`.
- [ ] AC5: Given an account with a password already exists for my Google email (matched ignoring case and accents), when I continue with Google and Google says the email is verified, then Google is connected to that account, I am signed in to it, and its email now counts as confirmed. If Google says the email is **not** verified, nothing is connected and I land on `/login` with "We couldn't sign you in with Google. Please sign in with your email and password."
- [ ] AC6: Given I cancel on Google's page, or Google sign-in fails for any reason (error from Google, expired or tampered request), then I land on `/login` with "Google sign-in was cancelled or failed. Please try again." Nothing is created or changed.
- [ ] AC7: A Google sign-in creates a normal session, the same as a password login: a new session id and CSRF token, a 12-hour maximum and the 30-minute idle timeout. "Remember me" doesn't apply to Google sign-in.

### Accounts without a password
- [ ] AC8: Given my account was created with Google (no password), when I try to sign in with my email and any password, then I see the usual "Invalid email or password". Nothing reveals that the account exists.
- [ ] AC9: Given my account has no password, then on `/account` the "Change password" card is replaced by a "Password" card: "You sign in with Google. To also sign in with a password, set one with 'Forgot password?' on the sign-in page." "Forgot password?" works for these accounts (`specs/email-auth.md` AC7–AC11), and afterwards I can sign in with either method.
- [ ] AC10: Given my account has no password, when I delete my account, then the dialog asks me to type my email address instead of a password. The wrong email shows "Email doesn't match". Everything else matches `specs/account-settings.md` AC10–AC13.

### Connect and disconnect on /account
- [ ] AC11: On `/account` there is a "Connected accounts" card. It shows "Google: connected as <google email>" or "Google: not connected".
- [ ] AC12: Given my account isn't connected, when I click "Connect Google" and approve, then Google is connected to **this** account, I come back to `/account` and see "Google connected". This works even if my Google email differs from my account email.
- [ ] AC13: Given that Google account is already connected to a **different** account in this app, when I try to connect it, then nothing changes and I see "This Google account is already connected to another account."
- [ ] AC14: Given Google is connected and my account has a password, when I click "Disconnect" and confirm, then Google is disconnected and I see "Google disconnected". Continuing with that Google account later creates or links an account by the AC2/AC5 rules.
- [ ] AC15: Given my account has no password, then "Disconnect" is disabled with the hint "Set a password first so you don't lose access to your account."

### Security
- [ ] AC16: The Google flow uses the standard OAuth 2.0 / OpenID Connect protections (state, nonce, PKCE). The app trusts only Google's verified token, never an email or ID sent by the browser.
  - The Google client secret lives only in backend settings (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`) and never reaches the frontend or the logs.
  - Signing in is protected against login CSRF: a sign-in started on another site can't sign me in to someone else's account.
- [ ] AC17: Accounts are matched by Google's stable account id (`sub`), not by email, after the first sign-in. If I change my Gmail address at Google, I still reach the same account.
- [ ] AC18: Security events go to the `security.audit` log: `GOOGLE_LOGIN_SUCCESS`, `GOOGLE_LOGIN_FAILURE` (reason), `GOOGLE_ACCOUNT_CREATED`, `GOOGLE_LINKED` (trigger: sign-in or connect), `GOOGLE_UNLINKED`. No tokens or codes are logged.
- [ ] AC19: The new `/api` endpoints return 401 when not signed in (except the public sign-in start and callback), 403 without a valid CSRF token, and follow the existing rate-limit style for the public ones.

### States
- [ ] AC20: While a redirect to Google is starting, the Google button is disabled and shows a spinner. The connect and disconnect buttons show loading and error states ("Something went wrong, please try again").

## Out of scope
- Other providers (Apple, GitHub, Microsoft). The design should make adding one easy, but none is built.
- Using Google to read anything else (contacts, calendar, profile picture). The only scopes are `openid email profile`.
- Remember me for Google sign-in (see AC7).
- Signing in with Google One Tap or the "Sign in with Google" pop-up widget. This is the standard redirect flow only.
- Roles or admin users (part A5).

## UI notes
- `/login` and `/register`: an "or" divider and a "Continue with Google" button below the form, following Google's branding guidelines (white button, Google "G" logo, the text "Continue with Google").
- `/account`: a new "Connected accounts" card between "Sessions" and "Delete account". For accounts without a password, the "Password" card replaces "Change password".
- Error and success messages after a Google round trip show as notices on `/login` or `/account`, like the existing ones.

## Constraints
- **Library:** Spring Security's OAuth2 / OpenID Connect client (`spring-boot-starter-oauth2-client`, a new dependency). No hand-written OAuth code.
- **Same origin:** the whole flow runs under `/api` (for example `/api/auth/oauth2/authorization/google` and the callback `/api/auth/oauth2/callback/google`). The dev proxy only forwards `/api` and production serves the frontend and API from one origin.
  - The redirect address registered at Google is `<APP_BASE_URL>/api/auth/oauth2/callback/google`.
  - In dev that is `http://localhost:4200/api/auth/oauth2/callback/google`.
- **After the callback**, the backend signs the user in with the same session setup as a password login (`SessionAuthenticator`) and redirects the browser to the frontend: `/tasks`, `/account`, or `/login` with a message code. The frontend learns about the Google feature from an API call, not from build settings.
- **Optional feature:** without `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`, Google sign-in is off. The button is hidden and the endpoints answer 404, so production can start without it.
- **Database:** a new table for connected sign-in providers (user, provider, provider account id, provider email, created time), deleted along with its user and unique per provider and provider account id. `users.password_hash` becomes nullable (accounts without a password).
- **Existing behaviour stays:** login, register, password reset, sessions and remember me are unchanged.
  - Change password and delete account keep their API.
  - The `User` response gains `hasPassword` and the connected providers (fields added, nothing renamed).
- **Tests never call the real Google.** Backend and e2e tests use a fake OpenID Connect provider (for example the `ghcr.io/navikt/mock-oauth2-server` Docker image via Testcontainers, and a dev-only docker-compose service). A new test library needs separate approval.
- **Proposed API** (the backend finalizes it in `contracts/openapi.yaml`):
  - `GET /api/auth/providers` → `{google: true|false}`
  - `GET /api/auth/oauth2/authorization/google?intent=login|connect` → 302 to Google
  - `GET /api/auth/oauth2/callback/google` → 302 to the frontend
  - `DELETE /api/account/connections/google` → 204
  - `POST /api/account/delete` accepts `{email}` for accounts without a password

## Approvals needed before build
- **New dependency:** `spring-boot-starter-oauth2-client` (official Spring Boot starter, free).
- **Database schema change:** migration `V7`. It adds a table for connected providers and makes `users.password_hash` nullable. This is a column change, not a drop, so existing data is untouched. The backend will propose the exact SQL first.
- **Authentication and security config:** the OAuth2 login flow and callback, login-CSRF protection, account linking by verified email, accounts without a password (password login, change password and delete all change behaviour for them), and the new audit events.
- **Infrastructure:** a fake OpenID Connect provider for dev and tests (a Docker image in docker-compose and Testcontainers, localhost only).

## Setting up Google (you do this once; not needed for building or testing)
1. Go to https://console.cloud.google.com, create a project (e.g. "Task App").
2. **APIs & Services → OAuth consent screen:** choose "External" and fill in the app name and your email. The scopes are `openid`, `email` and `profile`. While testing, add your own Google account under "Test users".
3. **APIs & Services → Credentials → Create credentials → OAuth client ID:**
   - Application type: "Web application".
   - Authorized redirect URI: `http://localhost:4200/api/auth/oauth2/callback/google`. Add your production address later, e.g. `https://yourdomain/api/auth/oauth2/callback/google`.
4. Copy the client ID and client secret. Set them as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` when you start the backend. Never commit them. With the `dev` profile, also set `GOOGLE_ISSUER_URI=https://accounts.google.com`: dev otherwise uses the fake Google (`docker compose up -d oidc`, http://localhost:8090).
5. Before going public, publish the consent screen (Google may ask for a short review).

## Open questions
None. All answered 2026-10-15 (see Decisions log).

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-15 | Spec approved, including: new dependency `spring-boot-starter-oauth2-client`; migration V7 (connected-providers table + `users.password_hash` nullable); the auth/security changes (OAuth2 flow, login-CSRF protection, linking by verified email, accounts without a password); a fake OpenID Connect provider in docker-compose and Testcontainers (dev/test, localhost only). Any other dependency still needs a separate approval | Approval gates | User |
| 2026-10-15 | An existing password account is linked automatically when Google says the email is verified | Open question 1 | User |
| 2026-10-15 | A taken Google display name gets a number added automatically | Open question 2 | User |
| 2026-10-15 | Accounts without a password confirm deletion by typing their email | Open question 3 | User |
| 2026-10-15 | Connect and disconnect on `/account` are part of A4 | Open question 4 | User |
| 2026-10-15 | Google sign-in ignores "Remember me" (normal session) | Open question 5 | User |
| 2026-10-15 | Redirect results use one query param `oauth` with a fixed set of codes (`google_failed`, `google_unverified`, `google_connected`, `google_in_use`, `google_connect_failed`); the frontend strips it right away, so a refresh doesn't repeat the message. Failed-connect text: "Couldn't connect Google. Please try again." | Frontend NEEDS | PM |
| 2026-10-15 | Disconnect returns 200 with the updated User. When Google is turned off, existing connections stay in `User` and the card is read-only | Frontend NEEDS | PM |
| 2026-10-15 | The Google flow starts with a top-level form POST carrying the CSRF token, not a GET | A GET start would let any website force a sign-in, sign-up or connect (login CSRF, AC16) | PM (within the approved security scope) |
| 2026-10-15 | V7 SQL accepted as within the approved scope: `users.password_hash` nullable + `user_connections` (id PK, user FK cascade, provider + provider_subject unique, one connection per user and provider, CHECK provider IN ('google'), `sub` as ascii_bin so ids are matched exactly, provider_email NOT NULL) | Same table and column the user approved; the extras are structural | PM (within the user's approval) |
| 2026-10-15 | A Google email that isn't verified never creates an account (`google_unverified`) | Otherwise anyone could claim someone else's email address | PM |
| 2026-10-15 | The dev profile points at the fake Google (`oidc` docker-compose service, `ghcr.io/navikt/mock-oauth2-server:6.0.3`, 127.0.0.1:8090); real Google in dev needs `GOOGLE_ISSUER_URI` | Like Mailpit; setup step 4 updated | PM |
| 2026-10-15 | Start and callback rate limits answer with the failure redirect, not a 429 (they're browser navigations); a password-less login compares against the dummy hash (no timing leak); half-configured Google settings or a non-https issuer with REQUIRE_HTTPS fail startup | Backend CONTRACT, contract 0.8.0 | PM |
| 2026-10-15 | Contract agreed in round 1 (frontend ACCEPT), `contracts/openapi.yaml` 0.8.0. Frontend: hidden form POST with `_csrf` read at submit time (Angular's `HttpXsrfTokenExtractor`); `/me` before a connect submit (a new AuthService method tells a 401 apart); reset the button spinner on `pageshow`. Backend to align the `OAuthResult` descriptions with the PM texts | See contract | PM |
| 2026-10-15 | Known limitation: if the app starts already signed in on `/login?oauth=…` (rare: a failed connect restored by remember-me, or another tab signed in), `guestGuard` sends the user to `/tasks` and the notice isn't shown. Nothing changed on the server; no guard change | Fixing it needs a new notice channel; low value | PM |

