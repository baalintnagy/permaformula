#!/bin/sh
set -eu
umask 077
mkdir -p "$HOME"
exec java --enable-native-access=ALL-UNNAMED -jar /app/service.jar \
    --server.port="${PORT:-5000}" "$@"
