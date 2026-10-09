# MySQL init scripts

Mounted read-only into the `db` container at `/docker-entrypoint-initdb.d` (see `docker-compose.yml`).

## Database users

| User | Rights | Used by |
|---|---|---|
| `taskapp` (`MYSQL_USER`) | ALL on `taskapp.*` | Flyway only (`spring.flyway.user`, env `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`) |
| `taskapp_app` (`APP_DB_USER`) | `SELECT, INSERT, UPDATE, DELETE` on `taskapp.*` | The app (`spring.datasource.username`, env `DB_USERNAME` / `DB_PASSWORD`) |

The app user cannot change the schema (`CREATE`, `DROP`, `ALTER`, ...). Schema changes go through Flyway migrations only.

## `01-app-user.sh`

Creates the app user `$APP_DB_USER` with password `$APP_DB_PASSWORD` and grants it
`SELECT, INSERT, UPDATE, DELETE` on `$MYSQL_DATABASE`. It connects as root (`$MYSQL_ROOT_PASSWORD`).
It is idempotent: running it again keeps the user, resets its password to `$APP_DB_PASSWORD` and re-applies the grant.

**It runs automatically only when the data volume is created** (first `docker compose up -d db`, or after
`docker compose down -v`). MySQL skips everything in `/docker-entrypoint-initdb.d` when the volume already holds a database.

The script is committed as executable (mode 755) with a `#!/bin/sh` shebang. Keep it that way: the MySQL
entrypoint executes `.sh` files that look executable and sources the others, and on Docker Desktop for Mac a
bind-mounted 644 file can look executable but then fails with "Permission denied" (container exits with 126).
The script works in both modes (no `exit`/`set` side effects when sourced).

### Existing volume (one-off)

```sh
docker compose up -d db    # recreates the container so it gets the mount and APP_DB_* env vars; data is kept
docker compose exec db sh /docker-entrypoint-initdb.d/01-app-user.sh
```

### Recovery: a fresh init failed

If the first start of a new volume failed (e.g. the container exited during init), the volume may be left
half-initialised: the next `up` reports healthy and skips the init scripts, so only `taskapp` exists. Either:

- run the one-off command above (keeps the data), or
- `docker compose down -v` and `docker compose up -d db` to start over. **This deletes all local data** in the volume.
