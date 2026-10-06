# Container deployment

Run `docker compose up --build -d` from the repository root with Docker's Linux engine running. The API is exposed on `127.0.0.1:5000`. Set `PORT` for a different host port and `PERMMATH_HOST` to the public origin used in permalinks. Public deployment needs a TLS reverse proxy and ingress/admission controls.

## Two services

The Maven build verifies all four modules. Docker builds separate targets:

- `runtime`: Spring API, UID/GID 10001, no Chromium or GraalJS. Fetches and sanitizes external CSS, prepares HTML, caches SVG and serves HTTP.
- `renderer`: gRPC service, UID/GID 10002, Chromium and GraalJS. Runs both HTML and formula jobs in disposable JVMs. Contains no API application or credentials.

The renderer has `network_mode: none`. gRPC uses Netty's Unix-domain socket transport with generated Protocol Buffers on `/run/permmath/render.sock`. The named volume contains only the socket; it is writable in the renderer and read-only in the API. Directory mode 0750 and socket mode 0660 restrict access to renderer group 10002, which is a supplementary API group. Do not mount this volume into other workloads. Plaintext gRPC is confined to this filesystem boundary; neither a renderer TCP port nor host socket is exposed.

External styles remain supported: only the API fetches them, using the existing HTTPS/public-address validation and size limits. The renderer receives sanitized embedded styles and serves trusted MathJax scripts on its private loopback interface. It cannot contact public or internal network destinations. The API has bounded fetch concurrency and still needs independent egress rules and client rate limits.

## Execution limits

`RENDERER_CONCURRENCY` defaults to 1. There is no rendering queue: occupied capacity returns gRPC RESOURCE_EXHAUSTED, exposed as HTTP 503. Both render paths have a 30-second maximum (`RENDERER_TIMEOUT_SECONDS`), further restricted by the API RPC deadline (`PERMMATH_RENDERING_TIMEOUT`, default 30s). The supervisor terminates job descendants on cancellation, timeout or failure. Fatal jobs cause service exit and Compose restarts the container, clearing its PID namespace, including orphan native processes. A supervisor is required when running outside Compose.

The API defaults to four preparation operations, two unique image generations and
two stylesheet batches, with no queue and HTTP 503 on occupied capacity. Configure
`PERMMATH_API_WORK_PREPARATION_CONCURRENCY`, `PERMMATH_API_WORK_GENERATION_CONCURRENCY`
and `PERMMATH_API_WORK_FETCH_CONCURRENCY`. `PERMMATH_API_WORK_TIMEOUT` defaults to
60s across preparation, fetching, shared-image waits and rendering. Cache hits skip
generation/fetching but decoding and identity preparation still use bounded slots.
Timeout returns HTTP 504 and interrupts work; non-cooperative DNS/parsers retain
their slots until exit. These are per-instance concurrency limits, not client rate
limits or proof of forcible API parser termination.

Content and SVG payloads are limited to 16 MiB; the gRPC envelope allows 64 KiB extra. Each job has a 1 GiB JVM heap; the supervisor has 256 MiB. Browser/native allocations are additionally bounded by the container. API decompression and endpoint inputs have separate byte/JSON limits; API admission separately bounds concurrent parsing and preparation. See `sec.md`.

Both containers use read-only root filesystems, dropped capabilities, no-new-privileges, private 512 MiB tmpfs, two CPUs and 256 processes/threads. The API has 2 GiB memory, a JVM heap sized at 50% of container memory (approximately 1 GiB), and a cache configured for 256 MiB and capped at 25% of JVM maximum heap; the renderer has 3 GiB memory and private 512 MiB shared memory, with no extra swap. Private `/tmp` mounts permit execution for Playwright and the native Netty epoll library used by Unix gRPC; both remain nosuid/nodev. Logs rotate; core dumps are disabled.

`RENDERER_CHROMIUM_SANDBOX=true` enables the Chromium sandbox. The bundled seccomp profile permits its user namespaces. The Linux host must permit unprivileged user namespaces; verify sandbox operation on the actual deployment host. Never replace a launch failure with privileged mode or an unsandboxed production browser. Images are digest-pinned; maintain JDK/browser/library/base-image updates.

## TCP deployments and development

The renderer also supports gRPC TCP. It binds to `127.0.0.1:9090` by default. Non-loopback binding requires mutual TLS: `RENDERER_BIND_ADDRESS`, `RENDERER_PORT`, `RENDERER_TLS_CERTIFICATE`, `RENDERER_TLS_KEY`, and `RENDERER_TLS_CLIENT_CA`. The API uses `PERMMATH_RENDERING_TARGET`, optional `PERMMATH_RENDERING_TLS_CA`, and the paired `PERMMATH_RENDERING_TLS_CERTIFICATE` / `PERMMATH_RENDERING_TLS_KEY`. TCP uses TLS by default on the API; `PERMMATH_RENDERING_PLAINTEXT=true` is an explicit local-development option.

Remote TCP deployment requires its own private network, authentication, egress and restart policy; Compose's network-none guarantee applies to the Unix-socket setup. Keep the renderer endpoint private: it accepts prepared documents and is not a public sanitization endpoint.

## Verification

`docker compose config --quiet` validates configuration. Check `/img?formula=x`, then use `/url` to create a styled descriptor and load its `/image` permalink. Confirm external styles survive rendering.

Inspect the renderer with `docker inspect`: NetworkMode must be `none`, user 10002, read-only root, resource limits and security options present. `/proc/net/dev` should contain only loopback. Application/browser files must be unwritable. Chromium must launch without `--no-sandbox` and with seccomp active. Repeat these checks on production hosts after runtime upgrades.

The socket healthcheck verifies listener-file presence for startup ordering; it is not a render probe. Monitor render latency/errors/restarts separately. After an abnormal shutdown a stale socket is removed on restart. Use `docker compose down --volumes` to remove this deployment's transient socket volume.

## Future asynchronous work

`render-contract` holds versioned messages and request IDs. `renderer-core` exposes transport-independent `RenderJobExecutor`; a future RabbitMQ consumer can call it without changing the rendering machinery. No broker, job database or asynchronous endpoint is implemented. Future async endpoints need durable job/result state, bounded admission, acknowledgement/retry/idempotency policies and a polling or notification contract. A rendering-indicator image should represent pending state, never replace a completed artifact in the SVG cache.
