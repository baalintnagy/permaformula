#!/bin/sh
set -eu
: "${PERMMATH_HOST:?API origin is required}"
: "${PERMMATH_IMAGE_HOST:?Image origin is required}"
PERMMATH_HOST=${PERMMATH_HOST%/}
PERMMATH_IMAGE_HOST=${PERMMATH_IMAGE_HOST%/}
export PERMMATH_HOST PERMMATH_IMAGE_HOST
# Expand only deployment origins, preserving native nginx variables.
envsubst '${PERMMATH_HOST} ${PERMMATH_IMAGE_HOST}' < /etc/nginx/nginx.conf.template > /tmp/nginx.conf
exec nginx -c /tmp/nginx.conf -e stderr -g 'daemon off;'
