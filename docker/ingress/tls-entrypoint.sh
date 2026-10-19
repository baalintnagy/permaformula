#!/bin/sh
# Direct TLS and ingress with the official nginx ACME module.
set -eu
umask 077
case "${1:-}" in '') [ "$#" -eq 0 ] || exit 1 ;; --check) [ "$#" -eq 1 ] || exit 1 ;; *) exit 1 ;; esac
: "${PERMAFORMULA_HOST:?HTTPS public origin required}"
: "${PERMAFORMULA_ACME_DIRECTORY:?Explicit HTTPS ACME directory required}"
: "${PERMAFORMULA_ACME_ACCEPT_TERMS:?Set true only after accepting the CA terms}"
[ "$PERMAFORMULA_ACME_ACCEPT_TERMS" = true ] || {
    echo 'ACME terms must be explicitly accepted.' >&2
    exit 1
}
[ "${PERMAFORMULA_DEPLOYMENT_PROFILE:-single-host}" = single-host ] || {
    echo 'TLS ingress supports single-host only.' >&2
    exit 1
}
[ -z "${PERMAFORMULA_INGRESS_TRUSTED_PROXY:-}" ] || {
    echo 'Direct TLS ingress must not trust a forwarding proxy.' >&2
    exit 1
}
[ "$(printf '%s\n' "$PERMAFORMULA_HOST" | wc -l)" -eq 1 ] \
    && [ "$(printf '%s\n' "$PERMAFORMULA_ACME_DIRECTORY" | wc -l)" -eq 1 ] || exit 1
printf '%s\n' "$PERMAFORMULA_HOST" | grep -Eq '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?$' || {
    echo 'Invalid HTTPS origin.' >&2
    exit 1
}
printf '%s\n' "$PERMAFORMULA_ACME_DIRECTORY" | grep -Eq '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[A-Za-z0-9_./-]*$' || {
    echo 'Invalid ACME directory.' >&2
    exit 1
}
PERMAFORMULA_TLS_HOSTNAME=${PERMAFORMULA_HOST#https://}
PERMAFORMULA_TLS_HOSTNAME=${PERMAFORMULA_TLS_HOSTNAME%%:*}
PERMAFORMULA_TLS_HOSTNAME=$(printf '%s' "$PERMAFORMULA_TLS_HOSTNAME" | tr '[:upper:]' '[:lower:]')
PERMAFORMULA_IMAGE_HOST=${PERMAFORMULA_IMAGE_HOST:-$PERMAFORMULA_HOST}
[ "$PERMAFORMULA_IMAGE_HOST" = "$PERMAFORMULA_HOST" ] || {
    echo 'TLS ingress requires a single public origin.' >&2
    exit 1
}
PERMAFORMULA_ACME_TRUSTED_CERTIFICATE=${PERMAFORMULA_ACME_TRUSTED_CERTIFICATE:-/etc/ssl/certs/ca-certificates.crt}
[ "$(printf '%s\n' "$PERMAFORMULA_ACME_TRUSTED_CERTIFICATE" | wc -l)" -eq 1 ] || exit 1
printf '%s\n' "$PERMAFORMULA_ACME_TRUSTED_CERTIFICATE" | grep -Eq '^/[A-Za-z0-9_./-]+$' || exit 1
[ -r "$PERMAFORMULA_ACME_TRUSTED_CERTIFICATE" ] || {
    echo 'ACME trust bundle is not readable.' >&2
    exit 1
}
[ -d /var/lib/nginx/acme ] && [ -w /var/lib/nginx/acme ] || {
    echo 'Mount a writable ACME state volume owned by UID 101.' >&2
    exit 1
}
export PERMAFORMULA_HOST PERMAFORMULA_IMAGE_HOST PERMAFORMULA_TLS_HOSTNAME PERMAFORMULA_ACME_DIRECTORY PERMAFORMULA_ACME_TRUSTED_CERTIFICATE
: "${PERMAFORMULA_EDGE_CLIENT_CONNECTIONS:=10}"
: "${PERMAFORMULA_EDGE_TOTAL_CONNECTIONS:=12}"
for value in "$PERMAFORMULA_EDGE_CLIENT_CONNECTIONS" "$PERMAFORMULA_EDGE_TOTAL_CONNECTIONS"; do
    case "$value" in '' | *[!0-9]* | 0*)
        echo 'Edge connection limits must be positive decimal integers.' >&2
        exit 1
        ;;
    esac
done
# Raising the measured 1 GiB envelope requires a fresh capacity test.
[ "$PERMAFORMULA_EDGE_CLIENT_CONNECTIONS" -le "$PERMAFORMULA_EDGE_TOTAL_CONNECTIONS" ] \
    && [ "$PERMAFORMULA_EDGE_TOTAL_CONNECTIONS" -le 12 ] || {
    echo 'Edge TCP budget must satisfy client <= total <= 12.' >&2
    exit 1
}
export PERMAFORMULA_EDGE_CLIENT_CONNECTIONS PERMAFORMULA_EDGE_TOTAL_CONNECTIONS
/usr/local/bin/ingress-entrypoint --configure-only
variables='${PERMAFORMULA_HOST} ${PERMAFORMULA_TLS_HOSTNAME} ${PERMAFORMULA_ACME_DIRECTORY} ${PERMAFORMULA_ACME_TRUSTED_CERTIFICATE} ${PERMAFORMULA_EDGE_CLIENT_CONNECTIONS} ${PERMAFORMULA_EDGE_TOTAL_CONNECTIONS}'
for part in acme-http tls-server http-server tcp-admission; do
    envsubst "$variables" <"/etc/nginx/$part.conf.template" >"/tmp/$part.conf"
done
# Reuse the existing route/security template, rather than fork its allowlist.
awk '
    function insert(file, line) { while ((getline line < file) > 0) print line; close(file) }
    { sub(/\r$/, "") }
    NR == 1 { print "load_module /usr/lib/nginx/modules/ngx_http_acme_module.so;" }
    /large_client_header_buffers/ { print "    large_client_header_buffers 1 20m;"; next }
    /^http \{/ {
        inHttp = 1
        insert("/tmp/tcp-admission.conf")
        print
        insert("/tmp/acme-http.conf")
        next
    }
    /^[[:space:]]*listen 8080;/ { insert("/tmp/tls-server.conf"); next }
    /^}$/ && inHttp { insert("/tmp/http-server.conf"); inHttp = 0 }
    { print }
' /tmp/nginx.conf >/tmp/nginx-tls.conf
nginx -t -c /tmp/nginx-tls.conf -e stderr
[ "${1:-}" != --check ] || exit 0
exec nginx -c /tmp/nginx-tls.conf -e stderr -g 'daemon off;'
