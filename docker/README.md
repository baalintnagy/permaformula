# Container deployment

Run `docker compose up --build -d` from the repository root with Docker's Linux engine running. The API is exposed on `127.0.0.1:5000`. Set `PORT` for a different host port and `PERMMATH_HOST` to the public origin used in permalinks. Public deployment needs a TLS reverse proxy and ingress/admission controls.

## Four services

The Maven build verifies all four modules. Docker builds separate targets:

- `runtime`: Spring API, UID/GID 10001, no Chromium or GraalJS. Fetches and sanitizes external CSS, prepares HTML, caches SVG and serves HTTP.
- `renderer`: gRPC service, UID/GID 10002, Chromium and GraalJS. Runs both HTML and formula jobs in disposable JVMs. Contains no API application or credentials.
- `stylesheet-egress`: Squid 7 from Alpine packages, running as `squid`. Its native ACLs allow CONNECT to public port 443 and reject internal, metadata, reserved and transition addresses, including any blocked answer in a mixed DNS result. No port is published, no TLS interception or content cache is used, and access logging is disabled.
- `ingress`: nginx from Alpine packages, running as `nginx`. Publishes the existing localhost HTTP port and forwards only to the API. Access logging is disabled to avoid recording formula/permalink queries. Proxy response timeouts are 65 seconds, beyond the API's 60-second deadline; SVG headers are preserved.

The renderer has `network_mode: none`. gRPC uses Netty's Unix-domain socket transport with generated Protocol Buffers on `/run/permmath/render.sock`. The named volume contains only the socket; it is writable in the renderer and read-only in the API. Directory mode 0750 and socket mode 0660 restrict access to renderer group 10002, which is a supplementary API group. Do not mount this volume into other workloads. Plaintext gRPC is confined to this filesystem boundary; neither a renderer TCP port nor host socket is exposed.

External styles remain supported. Apache HttpClient's `DefaultProxyRoutePlanner`
forces stylesheet downloads, imports and redirects through Squid; failure or denial
never falls back to a direct route. The HTTPS certificate/hostname is still verified
by Java against the destination. Squid independently resolves and checks all cached
destination addresses used for its connection. It uses a deny list rather than a
negated public-address allowlist, so mixed public/private answers reject the tunnel.

The API joins only `api-private`, an internal bridge with gateway mode `isolated`.
It has no external/default route, published port, or host bridge address. nginx
provides ingress and Squid provides egress; each joins its own separate external
network and `api-private`. The renderer still has no network. API-private peers
are limited to the API and these two proxies; mount no unrelated workloads there.
This configuration requires Docker Engine with isolated gateway mode (verified on
28.5.1). Unsupported engines must fail deployment rather than remove this setting:
an ordinary internal bridge still permits access to the host's bridge address.
[Docker gateway modes](https://docs.docker.com/engine/network/port-publishing/#gateway-modes),
[Squid ACLs](https://www.squid-cache.org/Doc/config/acl/).

Compose sets `PERMMATH_STYLESHEET_FETCH_PROXY_REQUIRED=true` and
`PERMMATH_STYLESHEET_FETCH_PROXY=http://stylesheet-egress:3128`. Missing/invalid
proxy configuration fails application startup; unavailable/denying proxies fail
the fetch. Standalone/Eclipse development defaults to direct fetching with the
existing DNS snapshot validation. To use a managed proxy there, set the same two
properties (environment variables or Spring configuration). Direct development
mode does not provide an independent network boundary.

The proxies use a digest-pinned Alpine base and distribution packages, read-only
filesystems, dropped capabilities and private 32 MiB tmpfs. Squid has 128 MiB memory,
0.5 CPU, 32 PIDs and an 8,192 file-descriptor limit; nginx has 256 MiB, 0.5 CPU and
32 PIDs. Keep their packages patched. Other deployment platforms need equivalent mandatory
proxy routing, network isolation and destination ACLs.

nginx overwrites `X-Forwarded-For` with its socket client address and removes
`Forwarded` and `X-Real-IP`. Its API-private IP is pinned to
`PERMMATH_INGRESS_IP` (default `172.30.86.2`) and is the API's only trusted forwarding
peer. The network subnet is `PERMMATH_API_SUBNET` (default `172.30.86.0/24`), with
dynamic allocation restricted to `PERMMATH_API_DYNAMIC_RANGE` (default
`172.30.86.128/25`). Keep the pinned ingress IP inside the subnet and outside the
dynamic range. Change all three together if they conflict with host networks, and use distinct subnets
for simultaneous Compose projects. `docker/egress/smoke.env` supplies a separate
test subnet. Existing deployments need `docker compose down` followed by
`docker compose up --build -d` to recreate the network when applying these IPAM
changes. Keep `SERVER_FORWARD_HEADERS_STRATEGY=none`; application or Tomcat
forwarding rewrites would hide the actual peer from this trust check. An upstream
load balancer/NAT must preserve client IPs or have an explicitly reviewed nginx
real-IP configuration; arbitrary incoming forwarded headers are never appended.

## Execution limits

`RENDERER_CONCURRENCY` defaults to 1. There is no rendering queue: occupied capacity returns gRPC RESOURCE_EXHAUSTED, exposed as HTTP 503. Both render paths have a 30-second maximum (`RENDERER_TIMEOUT_SECONDS`), further restricted by the API RPC deadline (`PERMMATH_RENDERING_TIMEOUT`, default 30s). The supervisor terminates job descendants on cancellation, timeout or failure. Fatal jobs cause service exit and Compose restarts the container, clearing its PID namespace, including orphan native processes. A supervisor is required when running outside Compose.

The API defaults to four preparation operations, two unique image generations and
two stylesheet batches, with no queue and HTTP 503 on occupied capacity. Configure
`PERMMATH_API_WORK_PREPARATION_CONCURRENCY`, `PERMMATH_API_WORK_GENERATION_CONCURRENCY`
and `PERMMATH_API_WORK_FETCH_CONCURRENCY`. `PERMMATH_API_WORK_TIMEOUT` defaults to
60s across preparation, fetching, shared-image waits and rendering. Cache hits skip
generation/fetching but decoding and identity preparation still use bounded slots.
Timeout returns HTTP 504 and interrupts work; non-cooperative DNS/parsers retain
their slots until exit. These do not forcibly terminate API parsers.

Bucket4j additionally limits each client to 600 requests/minute with a burst of
120 and 32 concurrent requests, and 60 new render attempts/minute with a burst of
10 and one concurrent generation. Configure `PERMMATH_RATE_LIMIT_REQUESTS_PER_MINUTE`,
`REQUEST_BURST`, `REQUEST_CONCURRENCY`, `GENERATIONS_PER_MINUTE`, `GENERATION_BURST`
and `GENERATION_CONCURRENCY` under that same prefix. Cache hits/shared results
spend only request tokens; both image endpoints share the render quota. HTTP 429
includes `Retry-After` and `Cache-Control: no-store`. Client state is capped at
100,000 identities (`PERMMATH_RATE_LIMIT_MAXIMUM_CLIENTS`) and expires after 1h
idle (`PERMMATH_RATE_LIMIT_EXPIRE_AFTER_ACCESS`), only after full bucket refill and
with no active requests/workers. Capacity exhaustion rejects new identities rather
than resetting existing quotas. Limits are in memory per API instance; replicas
need a shared Bucket4j backend, and clients behind NAT share quotas. No work queue
has been added.

Content and SVG payloads are limited to 16 MiB; the gRPC envelope allows 64 KiB extra. Each job has a 1 GiB JVM heap; the supervisor has 256 MiB. Browser/native allocations are additionally bounded by the container. API decompression and endpoint inputs have separate byte/JSON limits; API admission separately bounds concurrent parsing and preparation. See `sec.md`.

Both containers use read-only root filesystems, dropped capabilities, no-new-privileges, private 512 MiB tmpfs, two CPUs and 256 processes/threads. The API has 2 GiB memory, a JVM heap sized at 50% of container memory (approximately 1 GiB), and a cache configured for 256 MiB and capped at 25% of JVM maximum heap; the renderer has 3 GiB memory and private 512 MiB shared memory, with no extra swap. Private `/tmp` mounts permit execution for Playwright and the native Netty epoll library used by Unix gRPC; both remain nosuid/nodev. Logs rotate; core dumps are disabled.

`RENDERER_CHROMIUM_SANDBOX=true` enables the Chromium sandbox. The bundled seccomp profile permits its user namespaces. The Linux host must permit unprivileged user namespaces; verify sandbox operation on the actual deployment host. Never replace a launch failure with privileged mode or an unsandboxed production browser. Images are digest-pinned; maintain JDK/browser/library/base-image updates.

## TCP deployments and development

The renderer also supports gRPC TCP. It binds to `127.0.0.1:9090` by default. Non-loopback binding requires mutual TLS: `RENDERER_BIND_ADDRESS`, `RENDERER_PORT`, `RENDERER_TLS_CERTIFICATE`, `RENDERER_TLS_KEY`, and `RENDERER_TLS_CLIENT_CA`. The API uses `PERMMATH_RENDERING_TARGET`, optional `PERMMATH_RENDERING_TLS_CA`, and the paired `PERMMATH_RENDERING_TLS_CERTIFICATE` / `PERMMATH_RENDERING_TLS_KEY`. TCP uses TLS by default on the API; `PERMMATH_RENDERING_PLAINTEXT=true` is an explicit local-development option.

Remote TCP deployment requires its own private network, authentication, egress and restart policy; Compose's network-none guarantee applies to the Unix-socket setup. Keep the renderer endpoint private: it accepts prepared documents and is not a public sanitization endpoint.

## Verification

`docker compose config --quiet` validates configuration. Check `/img?formula=x`, then use `/url` to create a styled descriptor and load its `/image` permalink. Confirm external styles survive rendering.

Inspect the renderer with `docker inspect`: NetworkMode must be `none`, user 10002, read-only root, resource limits and security options present. `/proc/net/dev` should contain only loopback. Application/browser files must be unwritable. Chromium must launch without `--no-sandbox` and with seccomp active. Repeat these checks on production hosts after runtime upgrades.

For the independent egress smoke test, start a separate project on port 5017:

```powershell
docker compose --env-file docker/egress/smoke.env -p permmath-egress-smoke up --build -d
./scripts/test-egress.ps1
docker compose -p permmath-egress-smoke down --volumes
```

The script verifies isolated network membership/default-route absence, direct
internet denial, public HTTPS success, forbidden IP/port/DNS destinations and mixed
DNS answers using a test-only dnsmasq fixture. It then renders formula and Bootstrap
HTML through nginx and checks SVG headers/styles. It removes only fixtures it creates;
the caller shuts down the separate Compose project. CDN checks require internet
availability. This is not a complete native-proxy vulnerability or DNS-race audit.

To verify rate limits through nginx, use a fresh project with test-only small
quotas (do not apply this override to normal deployments):

```powershell
docker compose --env-file docker/egress/smoke.env -p permmath-rate-smoke -f compose.yaml -f docker/rate-limit/smoke.override.yaml up --build -d
./scripts/test-rate-limits.ps1
docker compose --env-file docker/egress/smoke.env -p permmath-rate-smoke -f compose.yaml -f docker/rate-limit/smoke.override.yaml down --volumes
```

The script checks HTTP 429 headers, continued cached SVG access after render-token
exhaustion and that changing incoming forwarding headers cannot grant another
allowance. The egress and rate-limit projects use the same test port/subnet; shut
down one before starting the other.

The socket healthcheck verifies listener-file presence for startup ordering; it is not a render probe. Monitor render latency/errors/restarts separately. After an abnormal shutdown a stale socket is removed on restart. Use `docker compose down --volumes` to remove this deployment's transient socket volume.

## Future asynchronous work

`render-contract` holds versioned messages and request IDs. `renderer-core` exposes transport-independent `RenderJobExecutor`; a future RabbitMQ consumer can call it without changing the rendering machinery. No broker, job database or asynchronous endpoint is implemented. Future async endpoints need durable job/result state, bounded admission, acknowledgement/retry/idempotency policies and a polling or notification contract. A rendering-indicator image should represent pending state, never replace a completed artifact in the SVG cache.
