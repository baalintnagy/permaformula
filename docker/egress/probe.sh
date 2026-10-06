#!/bin/sh
set -eu
case "$1" in
  tunnel)
    printf 'CONNECT %s HTTP/1.1\r\nHost: %s\r\n\r\n' "$3" "$3" |
      nc -w 5 "$2" 3128 | head -n 1
    ;;
  direct)
    if ip route | grep -q '^default'; then echo 'Unexpected default route'; exit 1; fi
    if nc -z -w 2 1.1.1.1 443; then echo 'Unexpected direct internet access'; exit 1; fi
    echo 'Direct egress blocked'
    ;;
esac
