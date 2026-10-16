#!/usr/bin/env bash
# One-time setup for running without Docker: creates the non-superuser app role and the databases
# used by the app (memora) and the test suite (memora_test). Run as a PostgreSQL superuser, e.g.
#   sudo -u postgres scripts/setup-local-db.sh
set -euo pipefail
user="${MEMORA_DB_USER:-memora}"; pass="${MEMORA_DB_PASSWORD:-memora}"
psql -v ON_ERROR_STOP=1 -d postgres <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$user') THEN
    CREATE ROLE "$user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD '$pass';
  END IF;
END \$\$;
SQL
for db in memora memora_test; do
  psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$db'" | grep -q 1 || psql -d postgres -c "CREATE DATABASE \"$db\" OWNER \"$user\""
done
echo "Role $user and databases memora, memora_test are ready."
