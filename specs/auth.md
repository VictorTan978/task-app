# Feature: Authentication

**Status:** DONE 2026-10-08

## Why
Tasks are personal, so the app must know who is using it. Users need to create an account, sign in, and sign out so each person sees only their own tasks.

## User story
As a visitor, I want to create an account and sign in with my email and password so that I have a private task list only I can see.

## Acceptance criteria

### Registration
- [x] AC1: Given I am on `/register`, when I enter a display name, a new email and a password of 8–72 characters and submit, then my account is created, I am signed in, and I land on `/tasks`. The header shows "Hi, <display name>".
- [x] AC2: Given an account exists for `bob@example.com`, when I register with `Bob@Example.com`, then I stay on `/register` and see "An account with this email already exists" under the email field.
- [x] AC3: Given I am on `/register`, when I submit an invalid email, a password shorter than 8 or longer than 72 characters, or an empty display name, then nothing is created and an error appears under each invalid field.

### Login
- [x] AC4: Given I have an account, when I enter my email (any letter case) and the correct password on `/login`, then I am signed in and land on `/tasks`.
- [x] AC5: Given I am on `/login`, when I enter a wrong password or an email that is not registered, then I stay on `/login` and see the same message in both cases: "Invalid email or password".

### Session and logout
- [x] AC6: Given I am signed in, when I refresh the page, then I am still signed in.
- [x] AC7: Given I am signed in, when I click "Log out", then I land on `/login`. Visiting `/tasks` afterwards sends me back to `/login`.
- [x] AC8: Given my session has expired, when the app makes any API call, then I am sent to `/login` with "Your session expired, please sign in again". After signing in I return to `/tasks`.

### Route and API protection
- [x] AC9: Given I am not signed in, when I visit `/tasks` or `/`, then I am sent to `/login`. Given I am signed in, when I visit `/`, `/login` or `/register`, then I am sent to `/tasks`.
- [x] AC10: Given I am not signed in, when any `/api/**` endpoint other than register, login and health is called, then it returns 401 with a ProblemDetail body.
- [x] AC11: A state-changing API call (POST/PUT/PATCH/DELETE) made without a valid CSRF token is rejected with 403.
- [x] AC12: Passwords are never stored or returned in plain text: the database holds only a BCrypt hash, and no API response contains a password or hash.

### States
- [x] AC13: While a login or registration request is in progress, the submit button is disabled and shows a loading indicator. If the server is unreachable, an error banner appears: "Something went wrong, please try again".

## Out of scope
- Forgot/reset password and email verification (both need an email service)
- Change password, change display name, delete account
- "Remember me" or sessions longer than the default 30-minute idle timeout
- Account lockout and rate limiting
- Sign in with Google or other OAuth providers
- Roles or admin users
- Deployment hardening (HTTPS-only `Secure` cookies, production profile, secrets management)

## UI notes
- `/register`: display name, email, password, a "Create account" button, and a link to `/login`.
- `/login`: email, password, a "Sign in" button, and a link to `/register`.
- Signed-in header: app name, "Hi, <display name>", and a "Log out" button.
- Plain SCSS with no UI library. Centered card forms.

## Constraints
- Session-cookie auth (HttpOnly) using Spring Security, with CSRF protection through the `XSRF-TOKEN` cookie that Angular's HttpClient reads automatically.
- Emails are trimmed and stored lowercase, and unique.
- Public endpoints: `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/health`. Swagger UI and `/v3/api-docs` are public in dev only.
- Errors use RFC 7807 `ProblemDetail`. Validation errors include the failing field names.
- Proposed API (the backend finalizes it in `contracts/openapi.yaml`): `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`.
- The existing `GET /api/health` must keep working.

## Approvals needed before build
- New Maven dependencies: `spring-boot-starter-security`, `spring-security-test` (test scope), `org.testcontainers:postgresql` and `junit-jupiter` (test scope).
- Security config change (adding Spring Security).
- Migration `V1__create_users.sql`.

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-08 | Personal task lists only, with no sharing in v1 | Smallest scope that makes auth meaningful; tasks get an owner from day one | User |
| 2026-10-08 | Email and password with open self sign-up, no email verification | No external services needed; easy to test | User |
| 2026-10-08 | Server-side session cookie and CSRF, not JWT | Simpler, resists XSS token theft, instant logout, no extra library | User |
| 2026-10-08 | Display name field; password 8–72 characters, BCrypt | Better header text; NIST length guidance; 72 is BCrypt's limit | User |
| 2026-10-08 | Automatic sign-in after registration | Saves a step | User |
| 2026-10-08 | Default 30-minute idle session; logout is the only account feature in v1 | Keep v1 small | User |
| 2026-10-08 | No lockout in v1; generic login error | App runs locally only; avoid revealing which emails are registered | User |
| 2026-10-08 | Session expiry redirects to login, then back to `/tasks`; unsaved edits are lost | Retrying the edit isn't worth the complexity for a rare case | User |
| 2026-10-08 | Backend tests use Testcontainers PostgreSQL 16 | Same engine as production; migrations run for real | User |
| 2026-10-08 | Swagger UI public in dev only | Usable while building, not exposed later | User |
| 2026-10-08 | Local-only v1; deployment hardening out of scope | Not deploying yet | User |
| 2026-10-08 | Auth built first, then tasks | Each feature testable on its own; no migration later to add owners | User |
| 2026-10-08 | Spec approved; approved Spring Security config, Maven dependencies (`spring-boot-starter-security`, `spring-security-test`, Testcontainers `postgresql` + `junit-jupiter`) and migration `V1__create_users.sql` | Approval gates for auth | User |
| 2026-10-08 | "Session expired" message shows only if the user was signed in during this tab and an API call then returns 401; a fresh load with a stale cookie goes to `/login` without the message | The client can't otherwise tell expired from never signed in (AC8 vs AC9) | User |
| 2026-10-08 | Login and register stay CSRF-protected; the app gets the `XSRF-TOKEN` cookie from its first GET (`/api/auth/me`) | No CSRF exemptions (AC11) | User |
| 2026-10-08 | Contract agreed in round 1 (frontend ACCEPT): `GET /api/auth/me` (200 User / 401), `POST /api/auth/register` (201 User; 400 VALIDATION_FAILED, 409 EMAIL_TAKEN), `POST /api/auth/login` (200 User; 401 INVALID_CREDENTIALS), `POST /api/auth/logout` (204). Errors are ProblemDetail with `code` and optional `errors[{field,code,message}]`; 403 CSRF_INVALID | See `contracts/openapi.yaml` | PM |
| 2026-10-08 | displayName 1–50 after trimming; email ≤ 254; password also ≤ 72 UTF-8 bytes; login doesn't check password rules | Sensible limits; BCrypt truncates past 72 bytes; login mustn't reveal the rules | PM |
| 2026-10-08 | User id is int64 (`bigint generated always as identity`) | Simple; no need for UUIDs in a local single-node app | PM |
| 2026-10-08 | Approved frontend auth work: auth service, route guards, 401 interceptor, Angular XSRF config, login/register pages, header | Approval gate for auth (frontend) | User |
| 2026-10-08 | V1 applied to the local dev DB during the backend build (approved migration) | Flyway runs on startup | — |
| 2026-10-08 | Tester verdict PASS on AC1–AC13 (41 backend tests, 46 unit tests, 21 e2e tests). AC8 was verified by simulating expiry, because no in-app API call reaches the 401 path yet; re-verify it with the tasks feature | Final verification | PM |
| 2026-10-09 | Switch database from PostgreSQL 16 to MySQL 8.4 (MySQL only, no dual support). V1/V2 rewritten in MySQL SQL in place (they were never applied to MySQL; fresh empty DB, no data migration). Maven deps swapped: `postgresql` → `mysql-connector-j`, `flyway-database-postgresql` → `flyway-mysql`, Testcontainers `postgresql` → `mysql`. Timestamps `DATETIME(6)` in UTC (not `TIMESTAMP`, which ends in 2038) | User request | User |
| 2026-10-09 | MySQL switch verified: backend 95 tests green; e2e 41/41 + 4 new MySQL regression tests (`frontend/e2e/mysql-regression.spec.ts`); all auth AC1–AC13 and tasks AC1–AC15 PASS; no console errors. Open: `utf8mb4_0900_ai_ci` makes email matching accent-insensitive (`jose@` = `josé@`), fix needs a new migration (pending user approval) | Final verification | PM |
