#!/bin/sh
# Runs once, when the PostgreSQL volume is first initialised.
# The application connects as a NON-superuser that owns its database: superusers bypass
# row-level security, so using one would silently disable Memora's second line of tenant isolation.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set app_user="$MEMORA_DB_USER" --set app_password="$MEMORA_DB_PASSWORD" --set app_db="$MEMORA_DB_NAME" <<'SQL'
CREATE ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'app_password';
CREATE DATABASE :"app_db" OWNER :"app_user";
SQL
