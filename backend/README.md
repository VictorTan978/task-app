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
