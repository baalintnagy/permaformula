#!/bin/sh
set -eu
: "${PERMMATH_CACHE_DB_PASSWORD:?Cache application password is required}"
# psql quotes the variable as a SQL literal; never interpolate secrets into SQL text.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 --set=cache_password="$PERMMATH_CACHE_DB_PASSWORD" <<'SQL'
CREATE ROLE permmath LOGIN PASSWORD :'cache_password';
REVOKE ALL ON DATABASE permmath FROM PUBLIC;
GRANT CONNECT ON DATABASE permmath TO permmath;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO permmath;
SQL
