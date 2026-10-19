#!/usr/bin/env bash
set -Eeuo pipefail
directory=$(cd "$(dirname "$0")" && pwd)
fixture=$(mktemp -d)
trap 'rm -rf -- "$fixture"' EXIT
cd "$fixture"
git init -q
printf '* text=auto eol=lf\nopaque.bin -text\n' >.gitattributes
printf '.runtime/\n' >.gitignore
printf 'one\r\ntwo\r\n' >'source with spaces.txt'
printf 'one\ntwo\r\n' >mixed.txt
printf '\000\r\n\377' >image.bin
printf 'binary override\r\n' >opaque.bin
mkdir .runtime
printf 'ignored\r\n' >.runtime/log.txt
binary_before=$(sha256sum image.bin opaque.bin .runtime/log.txt)
bash "$directory/../normalize-line-endings.sh"
printf 'one\ntwo\n' >expected.txt
cmp expected.txt 'source with spaces.txt'
cmp expected.txt mixed.txt
[[ $(sha256sum image.bin opaque.bin .runtime/log.txt) == "$binary_before" ]]
second=$(bash "$directory/../normalize-line-endings.sh" 2>&1)
[[ $second == 'Normalized 0 text files to LF.' ]]
echo 'PASS: CRLF/mixed normalization, spaced paths, binary/ignored preservation and idempotence.'
