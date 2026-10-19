#!/bin/sh
set -eu
case "${1:-}" in
    '') [ "$#" -eq 0 ] || exit 1 ;;
    --configure-only) [ "$#" -eq 1 ] || exit 1 ;;
    *)
        echo 'Unknown ingress entrypoint argument' >&2
        exit 1
        ;;
esac
: "${PERMAFORMULA_HOST:?API origin is required}"
: "${PERMAFORMULA_IMAGE_HOST:?Image origin is required}"
PERMAFORMULA_HOST=${PERMAFORMULA_HOST%/}
PERMAFORMULA_IMAGE_HOST=${PERMAFORMULA_IMAGE_HOST%/}
export PERMAFORMULA_HOST PERMAFORMULA_IMAGE_HOST
# Trust only an explicitly configured host proxy. Never accept directive fragments.
trusted_proxy=${PERMAFORMULA_INGRESS_TRUSTED_PROXY:-}
if [ -n "$trusted_proxy" ]; then
    printf '%s\n' "$trusted_proxy" | awk -F. 'NF != 4 {exit 1} {for(i=1;i<=4;i++) if($i !~ /^[0-9]+$/ || $i > 255) exit 1}' || {
        echo 'Invalid trusted proxy IPv4 address' >&2
        exit 1
    }
    printf 'set_real_ip_from %s;\nreal_ip_header X-Real-IP;\n' "$trusted_proxy" >/tmp/trusted-proxy.conf
else
    : >/tmp/trusted-proxy.conf
fi
printf 'map "$realip_remote_addr:$http_x_forwarded_proto" $permaformula_forwarded_scheme { default $scheme;\n' >>/tmp/trusted-proxy.conf
if [ -n "$trusted_proxy" ]; then
    printf '"%s:https" https;\n' "$trusted_proxy" >>/tmp/trusted-proxy.conf
fi
printf '}\n' >>/tmp/trusted-proxy.conf
case "${PERMAFORMULA_DEPLOYMENT_PROFILE:-single-host}" in
    single-host) template=/etc/nginx/nginx.single-host.conf.template ;;
    separate-origins) template=/etc/nginx/nginx.conf.template ;;
    *)
        echo 'Unknown deployment profile' >&2
        exit 1
        ;;
esac
# Native nginx controls; accept positive integers only, never directive fragments.
: "${PERMAFORMULA_INGRESS_CLIENT_CONNECTIONS:=64}"
: "${PERMAFORMULA_INGRESS_TOTAL_CONNECTIONS:=128}"
: "${PERMAFORMULA_INGRESS_HEADER_TIMEOUT_SECONDS:=15}"
: "${PERMAFORMULA_INGRESS_BODY_TIMEOUT_SECONDS:=30}"
: "${PERMAFORMULA_INGRESS_SEND_TIMEOUT_SECONDS:=30}"
: "${PERMAFORMULA_INGRESS_KEEPALIVE_TIMEOUT_SECONDS:=15}"
for value in "$PERMAFORMULA_INGRESS_CLIENT_CONNECTIONS" "$PERMAFORMULA_INGRESS_TOTAL_CONNECTIONS" \
    "$PERMAFORMULA_INGRESS_HEADER_TIMEOUT_SECONDS" "$PERMAFORMULA_INGRESS_BODY_TIMEOUT_SECONDS" \
    "$PERMAFORMULA_INGRESS_SEND_TIMEOUT_SECONDS" "$PERMAFORMULA_INGRESS_KEEPALIVE_TIMEOUT_SECONDS"; do
    case "$value" in '' | *[!0-9]* | 0*)
        echo 'Ingress limits must be positive decimal integers without leading zeros' >&2
        exit 1
        ;;
    esac
done
export PERMAFORMULA_INGRESS_CLIENT_CONNECTIONS PERMAFORMULA_INGRESS_TOTAL_CONNECTIONS \
    PERMAFORMULA_INGRESS_HEADER_TIMEOUT_SECONDS PERMAFORMULA_INGRESS_BODY_TIMEOUT_SECONDS \
    PERMAFORMULA_INGRESS_SEND_TIMEOUT_SECONDS PERMAFORMULA_INGRESS_KEEPALIVE_TIMEOUT_SECONDS
# Expand only listed settings, preserving native nginx variables.
envsubst '${PERMAFORMULA_HOST} ${PERMAFORMULA_IMAGE_HOST} ${PERMAFORMULA_INGRESS_CLIENT_CONNECTIONS} ${PERMAFORMULA_INGRESS_TOTAL_CONNECTIONS} ${PERMAFORMULA_INGRESS_HEADER_TIMEOUT_SECONDS} ${PERMAFORMULA_INGRESS_BODY_TIMEOUT_SECONDS} ${PERMAFORMULA_INGRESS_SEND_TIMEOUT_SECONDS} ${PERMAFORMULA_INGRESS_KEEPALIVE_TIMEOUT_SECONDS}' <"$template" >/tmp/nginx.conf
[ "${1:-}" != --configure-only ] || exit 0
exec nginx -c /tmp/nginx.conf -e stderr -g 'daemon off;'
