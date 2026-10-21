# Backend

Spring Boot 3 (Java 21). Run locally with `SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run`, test with
`./mvnw test`. Every setting and its environment variable is documented in
`src/main/resources/application.yml`; this file covers the ones that need more than a line.

## Roles and admin users

Every account has one role: `USER` (default), `ADMIN` or `SUPER_ADMIN` (see `specs/roles-admin.md`). Admins and
super admins get the "Admin" link and the `/admin/users` page. Nobody can make anyone `SUPER_ADMIN` from the app,
and nobody can change, disable or delete a super admin from the app.

### The first super admin: `ADMIN_BOOTSTRAP_EMAIL`

Set `ADMIN_BOOTSTRAP_EMAIL` to the email of an existing account and (re)start the backend:

```bash
ADMIN_BOOTSTRAP_EMAIL=alice@example.com SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

At startup, **only while no super admin exists yet**, that account becomes `SUPER_ADMIN` and the security log gets
`SECURITY event=ADMIN_BOOTSTRAPPED`. The email is matched like at login (ignoring letter case and accents).

- No account with that email yet: a warning is logged and the backend starts normally. Register the account, then
  restart; it is checked again on every start.
- The account is **disabled**: it is skipped with a warning (enable it first, see the SQL below).
- A super admin already exists: the setting does nothing (so it can't be used to take over the app later). You can
  leave it set or remove it.
- Empty or unset (default): nothing happens.

The user sees the "Admin" link on their next page load (or after signing in again).

### Emergency fallback: SQL

Connect to the database with the migration user (it has the rights the app user lacks), e.g. locally:

```bash
docker compose exec db mysql -u taskapp -ptaskapp taskapp
```

Make an account super admin (also the only way to add a **second** super admin):

```sql
UPDATE users SET role = 'SUPER_ADMIN' WHERE email = 'alice@example.com';
```

Remove a super admin (make them a normal user, or `'ADMIN'`):

```sql
UPDATE users SET role = 'USER' WHERE email = 'alice@example.com';
```

Enable a disabled account:

```sql
UPDATE users SET disabled_at = NULL WHERE email = 'alice@example.com';
```

Role changes made this way take effect on the user's next request; no restart needed. Keep at least one super
admin, or nobody can manage admins from the app until you run the first statement again.

### Admin rate limit

`RATE_LIMIT_ADMIN_ACTIONS_PER_USER_PER_MINUTE` (default 60): change requests (disable, enable, sign out
everywhere, change role, delete) per admin per minute; over it the API answers 429. The user list is not limited.
Raise it for e2e runs where many tests act as the same admin.

### Sharing rate limits

`RATE_LIMIT_INVITES_PER_USER_PER_HOUR` (default 30): list invitations per inviter per hour; over it the API answers
429. `RATE_LIMIT_INVITE_EMAILS_PER_ACCOUNT_PER_HOUR` (default 5): invitation emails per recipient account per hour;
silent (the invitation is still created, only the email is skipped). Raise both for e2e runs where many tests invite
as one user or invite the same address.

## Reminders (due times, notifications)

A background job (Spring's built-in scheduler; no extra dependency) fires due reminders: it creates the in-app
notification and, if the account is active, its email is verified, "Reminder emails" is on and the hourly cap allows
it, sends an email. Several app instances can share one database: each reminder is claimed with a row lock
(`SELECT ... FOR UPDATE SKIP LOCKED`), so it fires once. A reminder 1 hour or more late (the server was down) is
dropped; one more than `REMINDERS_LATE_AFTER` late is flagged "late". Email goes out after the claim, outside the
lock; a failed send is tried again up to 3 times, then the reminder is marked FAILED (logged without the address or
the text). Notifications older than 90 days are deleted hourly. A crash during a send can repeat that one email
(at-least-once), never the notification.

| Variable | Default | Meaning |
|---|---|---|
| `REMINDERS_ENABLED` | `true` | `false` = no reminder fires and nothing is cleaned up; the rest of the API works |
| `REMINDERS_POLL_INTERVAL` | `60s` | pause between two scans (use `2s` in e2e runs) |
| `REMINDERS_LATE_AFTER` | `5m` | a reminder fired later than this after its time is flagged `late` |
| `REMINDERS_BATCH_SIZE` | `100` | reminders handled per scan |
| `RATE_LIMIT_REMINDER_EMAILS_PER_ACCOUNT_PER_HOUR` | `20` | reminder emails per recipient account per hour, per app instance; silent (the notification is still created). Raise it (e.g. `1000000`) for e2e runs |

Reminder times are computed in each member's own time zone (`users.time_zone`, UTC until it is set); the due time is a
wall-clock value, so 09:30 is 09:30 for every member. The tests never start the timer (`app.reminders.enabled=false`
in the test settings) and call `ReminderProcessor.runOnce()` with a fixed clock.

## Deploy to Render

Layout: the backend runs as a Render **Docker web service** (`backend/Dockerfile`), the frontend as a Render
**Static Site**, the database is an **external MySQL 8** (Aiven, TiDB Serverless, ...) reached over SSL. The
Blueprint is `render.yaml` at the repository root (New > Blueprint in the Render dashboard; Render asks for every
variable marked `sync: false`). Never commit real values.

The static site rewrites `/api/*` to the backend, so the browser sees a single origin. This matters because the
session cookie is `SameSite=Lax` and `onrender.com` is a public suffix (two `*.onrender.com` services are
different sites). In `render.yaml`, replace `https://taskapp-backend.onrender.com` in the `/api/*` rewrite with
your backend's real URL.

### Environment variables (backend)

| Variable | Required | Value |
|---|---|---|
| `DB_URL` | yes | `jdbc:mysql://<host>:<port>/taskapp?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` (keep the time zone parameters) |
| `DB_USERNAME` | yes | the restricted app user (`taskapp_app`, see SQL below) |
| `DB_PASSWORD` | yes | its password |
| `DB_MIGRATION_USERNAME` | yes | the migration user (`taskapp`), used by Flyway at startup |
| `DB_MIGRATION_PASSWORD` | yes | its password |
| `MAIL_HOST` | yes | SMTP host of your provider |
| `MAIL_PORT` | no | default `587` (STARTTLS) |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | no, but providers need them | SMTP login |
| `MAIL_FROM` | yes | sender, e.g. `Task App <no-reply@example.com>`; must be allowed by your provider |
| `APP_BASE_URL` | yes | the **static site** URL, e.g. `https://taskapp-frontend.onrender.com` (must be `https://`; emailed links point here) |
| `ADMIN_BOOTSTRAP_EMAIL` | no | account to make super admin (see above) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | no | set both or neither |
| `PORT` | set by `render.yaml` | `8080`, the port the app listens on |

`REQUIRE_HTTPS` stays at its default (`true`): Render terminates TLS and sends `X-Forwarded-Proto`, which the app
trusts from Render's private network. `GET /api/health` is exempt from the HTTPS redirect, so Render's health check
works.

### Create the database and the two users

Create an empty database `taskapp` at the provider, then run this as the provider's admin user (it mirrors
`docker/mysql-init/01-app-user.sh`; pick your own passwords). Some providers (e.g. Aiven) only allow one admin
user or restrict `GRANT`; in that case create the users through the provider's console with the same rights.

```sql
CREATE DATABASE IF NOT EXISTS taskapp CHARACTER SET utf8mb4;

-- Flyway user: DDL, runs the migrations at every backend start
CREATE USER IF NOT EXISTS 'taskapp'@'%' IDENTIFIED BY '<migration-password>';
GRANT ALL PRIVILEGES ON taskapp.* TO 'taskapp'@'%';

-- App user: data only
CREATE USER IF NOT EXISTS 'taskapp_app'@'%' IDENTIFIED BY '<app-password>';
GRANT SELECT, INSERT, UPDATE, DELETE ON taskapp.* TO 'taskapp_app'@'%';
```

Flyway creates the tables on the first start. Check that the provider accepts connections from Render (no IP
allow-list, or allow Render's outbound IPs).

### Google sign-in

Register this redirect URI at Google: `<APP_BASE_URL>/api/auth/oauth2/callback/google`, e.g.
`https://taskapp-frontend.onrender.com/api/auth/oauth2/callback/google` (the static site URL, not the backend URL).

### Caveats of the free tier

- A free web service **sleeps after about 15 minutes without requests**; the next request waits ~1 minute for a
  cold start.
- While it sleeps, **reminders do not fire** (the scheduler runs inside the app); a reminder 1 hour or more late
  is dropped (see Reminders).
- Sessions and rate-limit counters are **in memory**: every restart or wake-up signs everyone out and resets the
  limits.
- 512 MB RAM: the image uses `-XX:MaxRAMPercentage=75`.
