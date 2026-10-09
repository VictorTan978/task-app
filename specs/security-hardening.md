# Feature: Security hardening

**Status:** APPROVED 2026-10-09 (in progress)

## Why
A security audit on 2026-10-09 checked the backend against a 15-item checklist and found gaps. This spec covers the fixes, in priority order. Item #15 (MySQL bound to localhost, API docs off by default) is a small change done separately.

## User story
As the owner of the task app, I want the backend to block password guessing, protect sessions in transit, record security events, limit database permissions, and refuse to run with dev secrets, so that a deployment is safe by default.

## Acceptance criteria

### A. Rate limiting (#8)
- [x] AC1: Given the same email has had 5 failed logins in the last 15 minutes, when a 6th login is attempted for that email (even with the right password), then the API returns **429** with a ProblemDetail `code: RATE_LIMITED` and a `Retry-After` header. Other emails are not affected.
- [x] AC2: Given one IP address has made 20 login requests in the last minute, when it makes another, then the API returns 429 `RATE_LIMITED`.
- [x] AC3: Given one IP address has made 5 register requests in the last hour, when it makes another, then the API returns 429 `RATE_LIMITED`.
- [x] AC4: Given a successful login, then the failure counter for that email is reset.
- [x] AC5: Given the login or register page gets a 429, then the user sees "Too many attempts. Please wait a few minutes and try again." and no field errors.
- [x] AC6: `contracts/openapi.yaml` documents the 429 response on login and register.

### B. HTTPS, Secure cookies, proxy headers (#1, #4)
- [x] AC7: Given production settings, then the session cookie and the `XSRF-TOKEN` cookie carry the `Secure` flag, including the cookie that logout clears.
- [x] AC8: Given production settings and a request that arrives through a TLS proxy with `X-Forwarded-Proto: https`, then the app treats it as HTTPS: the response includes `Strict-Transport-Security`, and the client IP used for rate limiting and logs is the real client from `X-Forwarded-For`.
- [x] AC9: Given production settings and a request with `X-Forwarded-Proto: http`, then the app redirects it to HTTPS.
- [x] AC10: Given dev settings on localhost over plain HTTP, then login, logout and CSRF work exactly as today.

### C. Security event logging (#11)
- [x] AC11: These events each write one log line with a fixed prefix (`SECURITY`), the event name, email (when known), client IP and request path: login success, login failure, registration, logout, rate-limit hit, 401 rejection, 403 rejection (CSRF or access denied).
- [x] AC12: No log line ever contains a password, password hash, session ID or CSRF token value.

### D. Least-privilege database users (#12)
- [ ] AC13: The app connects with a user that can only `SELECT, INSERT, UPDATE, DELETE` on `taskapp.*`. Flyway connects with a separate migration user.
- [ ] AC14: Given the app user, when it tries `DROP TABLE tasks` or `CREATE TABLE x`, then MySQL refuses.
- [ ] AC15: A fresh `docker compose up -d db` creates both users automatically. For the existing local database, one documented command creates the app user.
- [ ] AC16: All existing e2e tests still pass with the new users.

### E. No dev secrets outside dev (#7)
- [x] AC17: Given the app is started without the `dev` profile and without `DB_PASSWORD`, then it refuses to start with a clear error naming the missing variable.
- [x] AC18: Given the `dev` profile, then the app starts with the local docker-compose defaults, and API docs can be turned on, as today.
- [x] AC19: The run commands in `CLAUDE.md` (and anything that starts the backend, e.g. agent instructions) are updated to use the `dev` profile.

### F. Dependency scanning (#9)
- [x] AC20: `.github/dependabot.yml` checks Maven (`backend/`), npm (`frontend/`) and GitHub Actions weekly. It has no effect until the repo is pushed to GitHub.
- [x] AC21: One documented command scans backend dependencies for known CVEs (OWASP dependency-check), and `npm audit` is documented for the frontend.

## Out of scope
- Real TLS certificates or a reverse proxy. There is no server yet; the app is only made ready for one.
- Absolute session lifetime, "log out all devices", password change or reset.
- Encryption at rest and backups (#14). Done at the hosting level later.
- Distributed rate limiting (Redis). Counters are in memory, per app instance, and reset on restart.
- CAPTCHA, email alerts, and a log shipping or monitoring system.

## Constraints
- AC5 for login must keep showing "Invalid email or password" for wrong credentials, so it can't be used to find out which emails exist.
- Lockout is a temporary block per email, not a permanent account lock, so an attacker can't lock a real user out for good.
- Commit after each section (A to F) passes its checks (user request, 2026-10-09).

---

## Decisions log
_Filled in by the PM during the build._

| Date | Decision | Why | Approved by |
|---|---|---|---|
| 2026-10-09 | MySQL port bound to `127.0.0.1` and API docs off by default (#15), done as a small change | Database and API map were reachable from the local network | user |
| 2026-10-09 | Spec approved, including the approval gates for A (rate limiting, security config, contract), B (HTTPS/cookies, security config), C (logging in security handlers) and D (database users) | Audit gaps #8, #1, #4, #11, #12 | user |
| 2026-10-09 | Rate limiting is hand-written and in memory, with no new dependency | Enough for a single instance; avoids adding a dependency | user |
| 2026-10-09 | Secure by default: no profile means production settings, and local runs use `SPRING_PROFILES_ACTIVE=dev` | Forgetting a setting fails safe | user |
| 2026-10-09 | Dependabot config plus a documented OWASP command-line scan; no pom change | No new dependency; Dependabot works once the repo is on GitHub | user |
| 2026-10-09 | Rate limits are configurable through `RATE_LIMIT_*` env vars; the defaults are the spec values | The e2e suite registers many users from 127.0.0.1, so e2e runs raise the limits; exact limits are checked by backend tests and a dedicated e2e run | PM |
| 2026-10-09 | Build order A → F → E → B → C → D | F has no code dependencies; B's "production settings" need E's profiles; C logs the IPs from B and the 429s from A | PM |
| 2026-10-09 | Section A verified PASS (AC1–AC6). The real-limit e2e tests run only with `E2E_RATE_LIMIT=1` against a freshly started backend with default limits; mocked 429 tests always run | The full suite shares one IP | tester / PM |
| 2026-10-09 | Section F done: `.github/dependabot.yml` added and scan commands documented in CLAUDE.md. `npm audit`: 0 vulnerabilities. The OWASP command runs (first NVD download without a key takes hours; result reported separately) | Verified by the PM; config-only change | PM |
| 2026-10-09 | Section E verified PASS (AC17–AC19). `DB_URL` also has no default in production (a silent localhost fallback would connect to the wrong DB). A startup check plus a FailureAnalyzer give the clear message, because Spring's own error didn't name the variable | Fail loudly | backend-dev / tester / PM |
| 2026-10-09 | Accepted: `application-dev.yml` (with the dev password `taskapp`) is packaged in the jar | Same public dev-only value as docker-compose; only used when the dev profile is on | PM |
| 2026-10-09 | Section B verified PASS (AC7–AC10). One flag, `app.security.require-https` (`REQUIRE_HTTPS`, default on; dev forces it off), drives the Secure cookies and the redirect. Forwarded headers are trusted only from `TRUSTED_PROXIES` (default: private/loopback ranges). `GET /api/health` is exempt from the redirect for load-balancer probes | The proxy does TLS; the app redirect is a safety net, and the proxy should do its own 301/308 | backend-dev / tester / PM |
| 2026-10-09 | Section C verified PASS (AC11–AC12). Logger `security.audit`, format `SECURITY event=… [reason|limit] email= ip= path=`; INFO for successes, WARN for failures and rejections. LOGIN_FAILURE carries `reason=unknown_email|bad_password` (server-side only; the client response is unchanged). Values are percent-encoded against log injection | Grep-able, can be routed separately, attacker can't forge lines | backend-dev / tester / PM |
