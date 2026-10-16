#!/usr/bin/env bash
# Deletes ALL Memora data and starts from an empty schema plus demo seed data.
#   scripts/reset-db.sh docker   # Docker Compose: removes the database volume
#   scripts/reset-db.sh local    # local PostgreSQL: drops and recreates the database (needs a superuser via psql)
set -euo pipefail
mode="${1:-docker}"
case "$mode" in
  docker)
    docker compose down -v
    docker compose up -d --build
    ;;
  local)
    db="${MEMORA_DB_NAME:-memora}"; user="${MEMORA_DB_USER:-memora}"
    psql_cmd=(psql -v ON_ERROR_STOP=1 -d postgres)
    if [ "$(id -u)" = 0 ] && id postgres >/dev/null 2>&1; then psql_cmd=(su postgres -c "psql -v ON_ERROR_STOP=1 -d postgres"); fi
    sql="DROP DATABASE IF EXISTS \"$db\" WITH (FORCE); CREATE DATABASE \"$db\" OWNER \"$user\";"
    if [ "${psql_cmd[0]}" = su ]; then su postgres -c "psql -v ON_ERROR_STOP=1 -d postgres -c '$sql'"; else "${psql_cmd[@]}" -c "$sql"; fi
    echo "Database $db recreated. Start the backend; Flyway migrates and the demo data is seeded on startup."
    ;;
  *) echo "usage: $0 [docker|local]" >&2; exit 2 ;;
esac
