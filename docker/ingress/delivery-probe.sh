#!/bin/sh
set -eu
printf '%s\r\n' "GET $1 HTTP/1.1" 'Host: private-api' \
    'X-PermaFormula-Image-Delivery: 1' 'X-Forwarded-For: 198.51.100.1' \
    'Connection: close' '' | nc -w 5 permaformula 5000
