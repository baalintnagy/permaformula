#!/usr/bin/env bash
# Normalize source files using Git's binary detection and attribute overrides.
set -Eeuo pipefail
[[ ${1:-} != --help ]] || {
    echo 'normalize-line-endings.sh: convert repository text CRLF endings to LF'
    exit 0
}
[[ $# == 0 ]] || {
    echo 'No arguments expected.' >&2
    exit 1
}
cd "$(git rev-parse --show-toplevel)"
records=$(mktemp)
converted=$(mktemp)
trap 'rm -f -- "$records" "$converted"' EXIT
git ls-files --cached --others --exclude-standard --eol -z >"$records"
count=0
while IFS= read -r -d '' record; do
    metadata=${record%%$'\t'*}
    path=${record#*$'\t'}
    [[ $metadata != *'attr/-text'* ]] || continue
    [[ $metadata == *'w/crlf'* || $metadata == *'w/mixed'* ]] || continue
    [[ -f $path && ! -L $path ]] || continue
    sed 's/\r$//' "$path" >"$converted"
    cat "$converted" >"$path"
    count=$((count + 1))
done <"$records"
printf 'Normalized %s text files to LF.\n' "$count" >&2
