# Runtime and development reference

Commands and source paths below are relative to the repository root.

### Generated image caching

The SVG and JPEG endpoints cache successful output in memory using Caffeine
and in a persistent database. Local/IDE launches default to embedded SQLite;
Docker defaults to PostgreSQL. Concurrent requests for the same image in one API
process share one generation. Errors are not cached. Database entries survive
service restarts; the memory cache warms on demand.

```yaml
permaformula:
    image-cache:
        maximum-size: 256MB
        expire-after-access: 60d
        persistence:
            backend: sqlite # postgres in Docker, memory to disable persistence
            sqlite-path: .runtime/image-cache.sqlite
            maximum-size: 2GB
            cleanup-interval: 5m
```

The effective cache budget is the smaller of the configured maximum (256 MiB by default) and 25% of the JVM maximum heap. Startup logs report both configured and effective budgets. This reserves heap headroom for request processing; it does not bound concurrent work.

The memory budget uses an estimate of SVG string storage, keys and entry overhead;
it is not a hard limit on total JVM memory or in-flight rendering. Caffeine evicts
entries to stay within the budget. Images larger than the budget are returned but
not retained. Each cache hit resets the full configured TTL (60 days by default).
Memory expiry remains exact. Memory hits record their latest visit in a bounded
1024-key pending map without JDBC or image/source copies. PostgreSQL/SQLite expiry
is approximate: a background batch normally flushes visits every 30 seconds and
uses the actual visit age against the database clock. Its persisted expiry adds
up to 60 seconds of grace to cover batching. For very short TTLs the flush interval
is at most one quarter of TTL (minimum 1 ms), with twice that interval as grace.
Pending recent visits can keep a row readable until its flush; cleanup first
flushes visits and skips eviction when that flush fails. Touch updates never
reinsert deleted rows. A restart loses pending visits and does not grant expired
rows a fresh TTL. Outages/overload can lose more visit history; full pending maps
drop visits for new keys, keep updating existing keys, and report a bounded
capacity warning. Memory hits keep working independently of those failures.

Full source metadata is serialized lazily on a separate background worker (one
active and one queued job). A memory entry remembers successful publication;
cold database loads also recover that flag. Failures retry on later requests
after 30 seconds; queue/lock pressure retries after one second. Deleted/evicted
queued entries are not republished. The two pending publication jobs can retain
bounded source/output payloads in addition to the weighted memory cache.

Payload operations, including cold output validation, share two nonblocking
permits; library pages and small metadata checks have two separate permits.
Saturation returns 503 with Retry-After: 1; warm memory hits bypass this
budget. JDBC statement timeouts are at most two seconds or the remaining request
deadline, rounded to whole seconds (driver-dependent), with deadline checks around
stored-output validation. Pool acquisition keeps its existing one-second bound.
Parsing and response writes are not claimed to be forcibly interruptible at the
deadline. JPEG decoding/validation of memory hits uses the existing bounded API
preparation pool. See the [cache availability record](../docs/cache-availability.md).
Persistence now enforces configurable aggregate byte and row budgets within
write transactions (defaults: 2 GiB/10,000 rows). See [cache storage](../docs/cache-storage.md).
External stylesheets are fetched and validated during generation; unsupported content returns HTTP 400. Changes at the same URL become visible after eviction, expiry or restart.

Keys are SHA-256 hashes of a versioned rendering identity and the effective
descriptor, independent of the compressed URL spelling. HTML keys include the
resolved width. Formula-only keys depend on the exact formula text.
The HTML cache identity includes the security policy version. Bump the rendering identity version in
`GeneratedImageService` when a template, MathJax bundle, browser version or export
change affects output before introducing persistent storage.

`GeneratedImageStore` separates output storage from rendering and HTTP responses.
`CaffeineImageStore` uses `JdbcImageCache` underneath it. SQLite needs no separately
installed database and stores its file relative to the API process's working
directory. PostgreSQL runs privately in Compose with a `cache-data` named volume,
a restricted application role and a small connection pool. Copy `.env.example`
to `.env` and set distinct database passwords before starting Compose.
Cache SQL uses Spring JDBC named parameters and explicit Spring-managed write
transactions. Database-specific locks keep budget checks and writes atomic;
statement timeouts are computed from the current request deadline without changing
shared template settings.
Liquibase applies pending versioned YAML changesets before the cache starts,
preserving existing data and migration history.

The persistent budget covers UTF-8 SVG payload bytes, not database/WAL/index
overhead. Periodic cleanup removes expired and least-recently-used entries;
temporary growth between cleanups is possible. Oversized entries are returned
but not persisted. Stored SVG is revalidated on read; policy versions remain part
of the stable cache key. Startup fails if the configured database cannot be
initialized; later database errors fall back to memory/rendering with bounded
connection/query timeouts and redacted, throttled diagnostics.

Hit, miss, load and eviction counters are available through
`CaffeineImageStore.stats()`; a memory miss can be a database hit. There is no public
cache-management endpoint or distributed render lock. See
[deployment configuration](../docker/README.md) for configuration and database tests.

### Rendering

Chromium renders styled HTML with locally bundled MathJax 3.2.2 (SVG output).
It is the sole HTML rendering backend; no engine selection is required. The `/html-svg` and `/exp-svg` endpoints return SVG; `/html-jpg` and `/exp-jpg` return JPEG. Styled HTML is embedded using SVG foreignObject; `/exp-svg` returns pure formula SVG.
`imageWidth` is the layout width in pixels (500 by default); image height follows the content.
The legacy `default-dpi` setting is retained for configuration compatibility but
browser rendering uses CSS pixels rather than that value.

The project is a Maven reactor with four modules:

| Module | Responsibility |
|---|---|
| `permaformula-api` | HTTP endpoints, input validation, controlled CSS fetching, SVG sanitization and caching |
| `render-contract` | Versioned Protocol Buffers and generated gRPC client/server types |
| `renderer-core` | Chromium/GraalJS rendering, admission and warm-process supervision |
| `renderer-service` | Private gRPC endpoint, health service and deployment lifecycle |

Run `mvn verify` with JDK 25 to build and test all modules. Docker is the recommended way to run both services: `docker compose up --build -d`.

The API depends on `io.github.baalintnagy:local-exception-handler:0.9.4`.
If the artifact is unavailable from your configured Maven repositories, install
that version from its source project before building this reactor.

Scalar bounds for API-work, rate-limit and persistent-cache configuration use Jakarta
constraints. Their record constructors explicitly validate those constraints too,
so direct construction retains the same safeguards as Spring configuration binding.
Duration, refill, credential and resource checks remain explicit domain rules.

Java and XML use four spaces for indentation, as configured in `.editorconfig`.
Always use braces for Java `if`, `else` and loop bodies. Prefer streams, predicates
and method references for collection transformations and checks when they preserve
evaluation order, short-circuiting and exception handling. Keep explicit loops for
resource management, checked exceptions and indexed mutation. Use
`StringUtils.isNotBlank` for null-safe blank checks where Commons Lang is available.
The Maven build enables compiler lint warnings, excluding annotation-processor
claim checks for runtime framework annotations.

For Windows development, install Chromium with `./scripts/local/install-rendering.sh`, then build runtime dependencies:

To start the entire local stack from Bash (Git Bash on Windows; JDK 25, Maven,
Node.js/npm, cloudflared, and Chromium from `scripts/local/install-rendering.sh` required):

```bash
bash scripts/local/start-local.sh --admin-password 'your-secret-password'
# Reuse existing JARs and runtime dependencies on subsequent runs:
bash scripts/local/start-local.sh --skip-build --admin-password 'your-secret-password'
# Enable admin login for this API process:
bash scripts/local/start-local.sh --admin-password 'your-secret-password'
# From another terminal:
bash scripts/local/stop-local.sh
```

The launcher builds Java with tests skipped, creates API and UI tunnels, then starts
the renderer, API, and Vite in order, waiting for startup. It prints both public
URLs; logs for each process are under `.runtime/local/<session>/`. It accepts
`--ui-port`, `--startup-timeout-seconds`, and the named-tunnel settings from
`start-tunnel.sh` (`--tunnel-name`, `--public-origin`, `--config-path`, and UI equivalents).
Existing named tunnels must already route to the matching local ports.

`--admin-password` is mandatory and sets `PERMAFORMULA_ADMIN_PASSWORD` for the launched API. The password is passed through
the child process environment and is not written to the Eclipse launch file or logs.
Open `/admin.html` and sign in as `admin` with this password.

Ctrl+C, `stop-local.sh`, startup failure, or an unexpected component exit terminates
the session's child processes, including npm/Vite and Chromium descendants. Linux
uses bounded process-group TERM/KILL; Git Bash uses Windows `taskkill /T`. Keep
the launcher open and use Ctrl+C or `stop-local.sh`.
Occupied service ports and a second launcher are rejected. Tunnel configuration
updates remain in `frontend/.env.local` and the Eclipse API launch file, as with
the standalone tunnel script.

For manual service startup, build runtime dependencies:

```bash
mvn package dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-deps'
export RENDERER_CHROMIUM_SANDBOX='false' # Windows development
java --enable-native-access=ALL-UNNAMED -cp 'renderer-service/target/renderer-service-1.0.jar;renderer-service/target/runtime-deps/*' org.permaformula.renderer.RendererServiceMain
```

In another terminal, start the API with local plaintext gRPC explicitly enabled:

```bash
export PERMAFORMULA_RENDERING_PLAINTEXT='true'
java --enable-native-access=ALL-UNNAMED -jar permaformula-api/target/permaformula-api-1.0.jar
```

The renderer binds to loopback port 9090. The API defaults to TLS for TCP; see [deployment configuration](../docker/README.md) for mutual TLS and the network-isolated Docker Unix socket. Chromium is resolved only within `PLAYWRIGHT_BROWSERS_PATH` (default `.runtime/browsers`), not an installed Chrome/Edge browser. Playwright 1.59.0 selects the matching browser build.

Opt-in browser tests run with `mvn verify '-Drendering.tests=true'` after installation. The styled example matches the Builder and Swagger request at 500 pixels wide; comparison images go into module `target/rendering` directories. Normal tests do not launch Chromium.

Eclipse launch groups can start the renderer first, wait for standard output
matching `event=renderer_ready transport=tcp`, then launch the API with
`PERMAFORMULA_RENDERING_PLAINTEXT=true`. The readiness marker is written and flushed
on stdout after the gRPC listener starts. After changing the renderer, stop the
existing launch group and launch it again so the marker is emitted by the new process.

Both HTML and formula rendering use gRPC with request IDs and deadlines. The renderer uses a bounded Java work queue; full admission returns HTTP 503 with Retry-After, and an expired deadline or queue wait returns 504. HTML jobs use fresh browser contexts; formula jobs reuse initialized GraalJS/MathJax state with input passed as data. Content/output are bounded at 16 MiB. `RenderGateway` separates API generation from the transport, and `RenderJobExecutor` separates rendering from gRPC. A future RabbitMQ consumer can reuse the contract/core; new async endpoints and pending indicator images remain future work.

API work also uses immediate admission with no queue:

```yaml
permaformula:
    api-work:
        preparation-concurrency: 4
        generation-concurrency: 2
        fetch-concurrency: 2
        timeout: 60s
```

Preparation slots cover descriptor decoding, permalink creation and cache-identity
serialization. Generation slots cover unique cache misses, including HTML/CSS
preparation, rendering and final SVG validation. Fetch slots separately cover
stylesheet batches and their imports; documents without references do not use them.
These limits apply per API instance. Occupied capacity returns HTTP 503 immediately.

Per-client admission uses Bucket4j token buckets before decoding and before actual
cache-miss generation, plus concurrency caps. All endpoints, including cached SVGs,
consume request tokens; new HTML and formula render attempts share generation
tokens. Cached and shared in-flight results consume no generation tokens. Rejected
or failed generation attempts are not refunded. Configure these defaults under
`permaformula.rate-limit` (or the matching `PERMAFORMULA_RATE_LIMIT_*` environment variables):

| Setting | Default |
| --- | --- |
| `requests-per-minute` / `request-burst` | 600 / 120 |
| `request-concurrency` | 32 |
| `generations-per-minute` / `generation-burst` | 60 / 10 |
| `generation-concurrency` | 1 |
| `maximum-clients` / `expire-after-access` | 100,000 / 1h |
| `trusted-proxies` | Empty list |

Buckets refill continuously. Limit responses use HTTP 429, `Retry-After` in seconds
and `Cache-Control: no-store`; global admission still returns HTTP 503. The request
concurrency cap also covers duplicate callers waiting for a shared image. A worker
keeps its client generation slot until it actually exits, including after timeout.
Caffeine stores client state with bounded admission; a full registry rejects new
clients without evicting active allowances. Idle expiry must cover full refill of
both buckets, and active requests/workers pin their state until completion.

Client identity is the canonical socket IP, parsed by Guava without DNS lookups.
Only explicitly configured literal proxy IPs may supply one literal
`X-Forwarded-For` value; missing, duplicate, list or malformed trusted headers return
HTTP 400. Other forwarding headers are ignored. Keep
`server.forward-headers-strategy=none` and disable container-native remote-address
rewriting. Compose pins and trusts only nginx's private IP; nginx overwrites
`X-Forwarded-For`. Direct Eclipse requests use the peer address. Clients sharing a
NAT address share an allowance. Limits are per API instance and reset on restart;
multiple API replicas need a shared Bucket4j backend for a fleet-wide quota.

The renderer supervisor uses Java's `ThreadPoolExecutor`, `ArrayBlockingQueue`
and `AbortPolicy`: one worker by default, 16 waiting jobs and a 15-second maximum
queue wait. Configure `RENDERER_QUEUE_CAPACITY`, `RENDERER_QUEUE_WAIT_SECONDS` and
`RENDERER_QUEUE_MAX_RETAINED_BYTES` (default 67,108,864 bytes / 64 MiB). Capacity
zero disables waiting. The payload budget is capped at 25% of supervisor maximum
heap and conservatively charges admitted protobuf payloads, including running
jobs, at three times serialized size plus 1 KiB overhead each. It is a retention
estimate, not a bound on all JVM/transport/browser allocations.

Only actual cache-miss loaders reach the renderer. Cache hits and shared in-flight
results do not add jobs. Per-client generation slots remain occupied while waiting,
preventing one default client from filling the queue. Expired/cancelled queued
jobs are removed and cannot render later. A running worker retains admission until
its exchange finishes or its failed process and descendants have exited. Interruption
kills and replaces that worker. Queue expiry
or queued cancellation does not restart the renderer or interrupt another job.

The supervisor prestarts separate formula and HTML pools, each with
`RENDERER_CONCURRENCY` processes, while the shared execution cap stays at that
concurrency (one by default). Formula workers initialize a cached GraalJS source,
engine and context and perform a warmup render. HTML workers retain Chromium;
every request closes its fresh browser context and removes its temporary document.
Workers recycle after `RENDERER_WORKER_MAX_JOBS` successful jobs (default 100);
setting this to 1 retains single-use behavior with prestart. Initialization has a
separate `RENDERER_WORKER_STARTUP_SECONDS` limit (default 60, maximum 300).
Initialization and replenishment run asynchronously: `renderer_ready` means the
RPC listener is ready; an early request may still wait for initialization within
its existing deadline. Pooling increases idle memory and native process usage.
Review container limits before increasing concurrency. Stock-JDK GraalJS still
uses its interpreter; reuse removes repeated initialization without adding compiler
dependencies or changing the deployment JDK.

Content-free `event=render_timing` stderr records include `stage`, `pid` and
`durationMs`: queue wait, worker startup/acquisition/roundtrip, formula/HTML
initialization and rendering, and browser initialization/context/navigation/
typesetting wait/SVG extraction. Totals include their sub-stages and should not
be added together; navigation can include typesetting performed during page load.
The API also times stylesheet fetching, HTML preparation and the HTML RPC, so
slow external stylesheets can be distinguished from rendering work.
Run the opt-in cold/warm measurements and browser isolation checks with
`mvn -o -pl renderer-core,renderer-service -am -Drendering.tests=true test`.
Look for `event=render_benchmark`; results are uncached examples, not latency guarantees.

The API's two generation slots remain unchanged and include renderer waiting.
Thus one default API normally reaches at most one running and one queued render;
16 is the renderer-wide waiting ceiling, useful across trusted API instances or
deliberate admission tuning. Raising API generation concurrency also raises HTML/CSS
preparation pressure and requires a heap review. Replicas still need shared client
quotas. Queuing absorbs short bursts and does not increase rendering throughput.

One monotonic deadline starts in the servlet filter and follows work across its
threads. Preparation, fetches, shared-image waits and gRPC use the remaining
budget; expired work returns HTTP 504. The stylesheet network deadline remains at
most 15 seconds. The default renderer RPC budget is 45 seconds (queue plus execution),
while child execution remains capped at 30 seconds and the API deadline at 60 seconds.
Cache hits skip generation and fetching; decoding and identity preparation still
need preparation admission. A timed-out duplicate waiter does not cancel the shared
generation. Failed or timed-out generations are not cached.

Cancellation interrupts workers and gRPC. If DNS or a parser ignores interruption,
its admission slot remains occupied until that work exits. This bounds lingering
work but cannot forcibly stop it inside the API JVM. The deadline covers application
work, not slow HTTP request transmission or response delivery. Proxy connection
and delivery limits remain separate controls alongside the implemented client
limits, Compose egress rules and parser/layout complexity budgets.

Rendering waits for MathJax, fonts, and images, with a configurable timeout
(`permaformula.rendering.timeout`, default `45s`, including queue waiting; child execution defaults to 30 seconds). Java API validation aspects check user HTML against a presentation element/attribute allowlist; unsupported content returns HTTP 400. External and inline CSS are parsed with ph-css and validated against a presentation property/function allowlist. CSS variables, responsive media rules, supports rules, cascade layers and nested styles are retained. External stylesheets retain their application order; inline CSS follows them. Submitted images, SVG, links, scripts, forms and embedded documents are rejected rather than removed.

The `/html-svg` response type is `image/svg+xml` instead of
`image/jpeg`; existing compressed permalinks resolve to SVG without changing their
payload. HTML SVG exports use `foreignObject` with computed styles embedded,
and keep MathJax formulae as vector paths. They are intended for browsers;
SVG tools that do not support `foreignObject` should use `/exp-svg` for individual
formulae. Ordinary HTML text still uses the viewer's fonts.

Styled HTML export uses bundled html-to-image 1.11.13 through the small source
adapter in `tools/svg/export-styled-svg.js`. Node.js is needed only to rebuild
the checked-in bundle. The exporter copies computed styles and retains nested
MathJax SVG; browser network guards and API-side SVG validation still apply.

Handwritten renderer JavaScript lives in `tools/svg`: browser startup and guards
are named modules, and both renderers share the MathJax safety configuration.
Run `npm run check` there to lint, check formatting and verify the checked-in bundles.
The template contains only trusted script references. Java reads browser results
through `window.PermaFormulaRenderer`; no Node.js process runs in the service.

Both renderers enable MathJax's safe extension. The API independently validates
SVG before caching or serving it, using secure JDK StAX XML parsing, OWASP URL
filtering and ph-css. The presentation policy preserves computed styles,
foreignObject, MathML, glyph paths, local fragment references and HTTPS links.
Active elements, unsafe links, external resources, DTDs and XML entities are
rejected; unsupported CSS declarations are removed. Output is limited to 16 MiB,
depth 256 and 100,000 elements. Both cache identities include the output-policy
version. `/html-svg` and `/exp-svg` send sandbox CSP, `nosniff` and `no-referrer` headers.

External stylesheets are fetched by a controlled HTTP client, with HTTPS on port 443, normal TLS certificate/hostname verification, and public IP addresses only. The connection uses the validated DNS addresses, so it cannot re-resolve an unchecked address. Every redirect is validated; HTTP downgrades, credentials and private/loopback/link-local/reserved destinations are rejected. CSS imports use the same fetcher and resolve relative to the final stylesheet URL.

Limits are 8 top-level references, 16 fetched resources including imports, 3 redirects per resource, 3 import levels, 1 MiB per response, 4 MiB combined decoded CSS, and a shared 15-second fetch deadline. Responses must be `text/css`; compressed responses are rejected (requests ask for identity encoding). CSS resource URLs, external fonts, executable expressions and unknown at-rules are removed.

Browser contexts block service workers, WebSockets and all requests except the current document and bundled rendering scripts. Only one document load in the approved main frame is allowed. Reloads and redirects are denied, extra pages close, and any child frame terminates the render, including srcdoc and data frames. Before document scripts run, an initialization script disables workers, service-worker registration, WebRTC constructors, WebTransport, WebSocketStream and window.open. The asset server disables DNS prefetch hints. A restrictive CSP supplies another boundary. Browser channel tests exercise these guards independently of API content validation and CSP; they do not establish OS-level network isolation.

Docker Compose isolates the renderer in a separate container with no network access. The API fetcher has bounded admission but still needs independent deployment egress and client rate controls.

### Logging

The endpoint aspect logs controller completion at INFO with the HTTP method, path,
handler, status and duration. Failures are logged at WARN with the exception type;
the exception handler determines the final error response. Requests rejected before
controller invocation are handled by Spring MVC and its exception handler.
Formula parameters, compressed payloads and SVG bodies are not included.

The image cache logs initialization, misses (`cache_miss`), successful generation
(`cache_generated`) and hits (`cache_hit`) at INFO. Generation failures use WARN;
removal causes use DEBUG. Hits include both cached output and output shared from
another concurrent request, identified by `source=cached_or_shared`.

Enable detailed endpoint and cache logging with:

```yaml
logging:
    level:
        org.permaformula.logging: DEBUG
        org.permaformula.rendering.cache: DEBUG
```
