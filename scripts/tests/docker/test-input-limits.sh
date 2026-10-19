#!/usr/bin/env bash
set -Eeuo pipefail
directory=$(cd "$(dirname "$0")" && pwd)
helper="$directory/test-input-limits.cjs"
command -v cygpath >/dev/null && helper=$(cygpath -w "$helper")
exec node "$helper" "$@"
