FROM alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0 AS egress
RUN apk add --no-cache squid
COPY --chmod=0444 docker/egress/squid.conf /etc/squid/squid.conf
COPY --chmod=0444 docker/egress/blocked-destinations.txt /etc/squid/blocked-destinations.txt
USER squid
ENTRYPOINT ["squid", "-N", "-f", "/etc/squid/squid.conf"]

FROM alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0 AS ingress
RUN apk add --no-cache nginx gettext-envsubst
COPY --chmod=0444 docker/ingress/nginx.conf /etc/nginx/nginx.conf.template
COPY --chmod=0444 docker/ingress/nginx.single-host.conf /etc/nginx/nginx.single-host.conf.template
COPY --chmod=0444 docker/ingress/api-proxy.conf docker/ingress/image-proxy.conf /etc/nginx/
COPY --chmod=0555 docker/ingress/entrypoint.sh /usr/local/bin/ingress-entrypoint
USER nginx
ENTRYPOINT ["/usr/local/bin/ingress-entrypoint"]

# Test-only DNS fixture: dnsmasq supplies mixed public/private answers, not a custom DNS server.
FROM alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0 AS test-dns
RUN apk add --no-cache dnsmasq
COPY --chmod=0444 docker/egress/test-dns.hosts /etc/test-dns.hosts
USER nobody
ENTRYPOINT ["dnsmasq", "--keep-in-foreground", "--no-resolv", "--no-hosts", "--addn-hosts=/etc/test-dns.hosts", "--pid-file=/tmp/dnsmasq.pid"]
