#!/bin/sh
# Creates the restricted application database user (data only: SELECT, INSERT, UPDATE, DELETE).
# The MYSQL_USER created by the image (taskapp) keeps ALL on the database and is used only by Flyway.
#
# Runs automatically on a fresh volume. The MySQL entrypoint *executes* it when it looks executable and
# *sources* it otherwise, so it works both ways: committed as mode 755 with a shebang (Docker Desktop for
# Mac can make a 644 bind-mounted file look executable, so relying on "sourced" is not safe), and free of
# "exit"/"set" side effects for the sourced case.
# Idempotent: for an existing volume run it by hand (see README.md in this folder):
#   docker compose exec db sh /docker-entrypoint-initdb.d/01-app-user.sh
#
# Reads: MYSQL_ROOT_PASSWORD, MYSQL_DATABASE, APP_DB_USER, APP_DB_PASSWORD (container env, set in
# docker-compose.yml, so available whether executed or sourced).
# No "exit" and no "set" here: when sourced, that would affect the image's entrypoint shell.
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is not set}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is not set}"
: "${APP_DB_USER:?APP_DB_USER is not set}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD is not set}"

# Escape for a single-quoted SQL string literal (backslash and quote).
app_user_sql_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e "s/'/''/g"
}

app_user_name=$(app_user_sql_escape "$APP_DB_USER")
app_user_pw=$(app_user_sql_escape "$APP_DB_PASSWORD")
app_user_db=$(printf '%s' "$MYSQL_DATABASE" | sed -e 's/`/``/g')

echo "01-app-user.sh: creating/updating user '$APP_DB_USER' with SELECT, INSERT, UPDATE, DELETE on \`$MYSQL_DATABASE\`.*"

# MYSQL_PWD instead of -p keeps the password off the command line (and avoids the CLI warning).
MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket -uroot <<SQL
CREATE USER IF NOT EXISTS '$app_user_name'@'%' IDENTIFIED BY '$app_user_pw';
ALTER USER '$app_user_name'@'%' IDENTIFIED BY '$app_user_pw';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`$app_user_db\`.* TO '$app_user_name'@'%';
SQL

unset app_user_name app_user_pw app_user_db
unset -f app_user_sql_escape
