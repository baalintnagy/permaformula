#!/usr/bin/env bash
set -Eeuo pipefail
directory=$(cd "$(dirname "$0")" && pwd)
command -v node >/dev/null || {
    echo 'Node.js is required for the HTTP/socket regression checks.' >&2
    exit 1
}
if command -v cygpath >/dev/null; then
    directory=$(cygpath -w "$directory")
fi
exec node "$directory/test-image-delivery.cjs" "$@"
