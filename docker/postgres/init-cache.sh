#!/bin/sh
set -eu
: "${PERMAFORMULA_CACHE_DB_PASSWORD:?Cache application password is required}"
# psql quotes database identifiers and password literals; never interpolate secrets.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 --set=cache_database="$POSTGRES_DB" \
    --set=cache_password="$PERMAFORMULA_CACHE_DB_PASSWORD" <<'SQL'
CREATE ROLE permaformula LOGIN PASSWORD :'cache_password';
REVOKE ALL ON DATABASE :"cache_database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"cache_database" TO permaformula;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO permaformula;
SQL
