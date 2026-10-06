#!/bin/sh
# Test-only client; use two independent Docker socket identities to respect client quotas.
set -eu
wget -q -O /tmp/result.svg "http://ingress:8081/img?formula=${QUEUE_TEST_FORMULA:?formula required}"
grep -q '<svg' /tmp/result.svg
echo 'PASS: concurrent unique render returned SVG'
