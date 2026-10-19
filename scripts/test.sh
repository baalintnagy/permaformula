#!/usr/bin/env bash
set -Eeuo pipefail
shopt -s globstar nullglob
directory=$(cd "$(dirname "$0")" && pwd)
for script in "$directory/"**/*.sh; do
    bash -n "$script"
done
node_directory=$directory
command -v cygpath >/dev/null && node_directory=$(cygpath -w "$directory")
for helper in "$directory/"**/*.cjs; do
    file=$helper
    command -v cygpath >/dev/null && file=$(cygpath -w "$helper")
    node --check "$file"
done
node --test "$node_directory/tests/docker/test-utils.test.cjs"
bash "$directory/tests/test-line-endings.sh"
for script in "$directory/tests/docker/test-"*.sh; do
    bash "$script" --help >/dev/null
done
bash "$directory/local/test-node.sh"
bash "$directory/local/test-local.sh"
echo 'PASS: all offline script checks; no PowerShell required.'
