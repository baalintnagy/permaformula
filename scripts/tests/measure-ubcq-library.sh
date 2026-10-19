#!/usr/bin/env bash
# Opt-in local measurement. Creates and removes its own disposable PostgreSQL.
set -Eeuo pipefail
if [[ ${1:-} == --help ]]; then
    echo 'measure-ubcq-library.sh: measure library UBCQ bytes, PostgreSQL transfer, allocations and held-response memory locally'
    exit 0
fi
[[ $# == 0 ]] || {
    echo 'No arguments expected.' >&2
    exit 1
}
cd "$(dirname "$0")/../.."
container="permaformula-ubcq-measure-$(date +%s)-$$"
created=false
cleanup() {
    [[ $created == false ]] || docker rm -fv "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT
export PERMAFORMULA_POSTGRES_TEST_PASSWORD="$(openssl rand -hex 16)"
docker run -d --name "$container" --memory 768m -p 127.0.0.1::5432 \
    -e POSTGRES_DB=permaformula -e POSTGRES_USER=permaformula \
    -e POSTGRES_PASSWORD="$PERMAFORMULA_POSTGRES_TEST_PASSWORD" \
    postgres:17-alpine@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24 >/dev/null
created=true
ready=false
for ((attempt = 0; attempt < 30; attempt++)); do
    if docker exec "$container" pg_isready -U permaformula -d permaformula >/dev/null 2>&1; then
        ready=true
        break
    fi
    sleep 1
done
[[ $ready == true ]] || {
    echo 'Disposable PostgreSQL did not become ready.' >&2
    exit 1
}
port=$(docker port "$container" 5432/tcp)
port=${port##*:}
export MAVEN_OPTS="${MAVEN_OPTS:-} -Xms256m -Xmx1024m --enable-native-access=ALL-UNNAMED"
mvn -o -pl permaformula-api -am -Dpermaformula.build.directory=target/ubcq-measure \
    -Dtest=UbcqLibraryMeasurementTests -Dsurefire.failIfNoSpecifiedTests=false \
    -Dubcq.measure=true -Dubcq.measure.disposable=yes \
    "-Dubcq.measure.url=jdbc:postgresql://127.0.0.1:$port/permaformula" -DforkCount=0 test
