# MathRenderAPI

MathRenderAPI generates SVG images from compressed formula and styled HTML descriptors. Its permalink generator accepts [AsciiMath](https://asciimath.org/) or HTML and optional CSS, then stores the descriptor in a compressed URL payload.

## Features
- Generate images from [AsciiMath](https://asciimath.org/)-encoded formulas
- Customize formula appearance with CSS
- Create permalinks for any formula
- Generate images from previously created permalinks

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /url?formula=...` | Generate a compressed permalink. Optional `imageWidth`, `style`, and repeated external HTTPS `styleRefs` parameters. |
| `GET /image?ubcq=...` | Render compressed styled HTML with backtick-delimited [AsciiMath](https://asciimath.org/) as SVG. |
| `GET /img?formula=...` | Render an uncompressed plain [AsciiMath](https://asciimath.org/) formula as standalone SVG, without a browser. |
| `GET /url-encode?text=...` | Encode text as a UTF-8 query parameter value and return plain text. |

`/image` uses `ubcq`: a GZIP-compressed UTF-8 JSON image descriptor,
Base64-encoded and URL-encoded. `/url` always generates this format, including
for short formulas. The `compressUrlQuery` option and uncompressed input modes
have been removed. `/image` requires `ubcq`. The public `/decode` endpoint and
the former `/image-cq` and `/decode-cq` route names are no longer available.

### Query-value encoding: `/url-encode`

```sh
curl --get --data-urlencode 'text=x + y = 50%' http://localhost:8086/url-encode
# x+%2B+y+%3D+50%25
```

Java's `URLEncoder` applies UTF-8 form/query-value encoding: spaces become `+`,
literal `+` becomes `%2B`, and reserved characters and non-ASCII bytes are escaped.
The result represents one query parameter value, so URL delimiters in the input
are escaped too. Supply original text in Swagger or use normal query encoding
when calling the endpoint; the servlet decodes the incoming parameter once.
Empty text is accepted; missing `text` returns 400. Input is capped at the existing
4 MiB content limit measured in UTF-8, bounding encoded output to 12 MiB. Normal
ingress request-line/header limits also apply. The operation uses bounded API
preparation and the request deadline/quotas. It returns `text/plain;charset=UTF-8`
with no-store, nosniff and no-referrer headers and uses no rendering quota or cache.

### Formula-only SVG: `/img`

Send plain [AsciiMath](https://asciimath.org/) directly, without HTML or backtick delimiters:

```sh
curl --get --data-urlencode 'formula=sqrt(x^2 + y^2)' http://localhost:8086/img --output formula.svg
```

`/img` accepts only a required `formula` parameter. The formula determines its
natural dimensions; there are no width or styling options. The former compressed
`ubcq` input is no longer supported by this endpoint. `/url` continues to generate
compressed permalinks for `/image`.

Response: `200 OK`, `Content-Type: image/svg+xml`. The standalone formula SVG
contains embedded glyph paths and can be used as an HTML image source. Missing
or blank formulas return `400 Bad Request`. Query parameters must still be
URL-encoded, including literal `+` characters.

Formula, HTML, CSS and stylesheet references are preserved without whitespace
normalization. The historical `forumla` JSON spelling remains in compressed
payloads for `/image`.

This endpoint runs bundled MathJax 3.2.2 with its lightweight DOM adapter in
GraalJS in a disposable JVM inside the separate renderer service. It launches no browser and needs no
Node.js installation at runtime. Build instructions are in
[`tools/svg/README.md`](tools/svg/README.md).

## Usage

### Error responses

Exception handling is provided by
[`local-exception-handler`](https://github.com/baalintnagy/local-exception-handler)
(`io.github.baalintnagy:local-exception-handler:0.9.1b`, from Maven Central).
Handled errors return `application/problem+json` with standard Problem Detail
fields (`type`, `title`, `status`, `detail`, `instance`), plus an `id` and
`timestamp`. Constraint errors include `violations`; illegal arguments include
`errorType: BUSINESS_RULE_VIOLATION`.

This replaces the previous custom error JSON format and `permmath.exception`
configuration. The service uses Spring Boot 4 / Spring Framework 7, as required
by the exception-handler library.

Simply send a GET request to the relevant endpoint with your desired parameters.

Generate a permalink with /url, then request the returned link to render it.

The default production deployment uses one hostname for the Builder UI, API and
images. Configure `PERMMATH_HOST` to its public HTTPS origin (a provider-assigned
hostname is sufficient). Compose exposes nginx on `127.0.0.1:5000`; terminate TLS
in front of that listener. `/image` and `/img` still pass through credential-stripping
image ingress, mandatory trusted-ingress validation, sanitized SVG output and a
restrictive CSP sandbox. Keep the API and renderer private.

The previous two-origin deployment is preserved as `separate-origins` in
`compose.separate-origins.yaml`. It additionally isolates image cookies using
separate registrable domains. See [deployment configuration](docker/README.md)
for both profiles, security properties and validation.

## Getting Started for Developers

### Web UI

PermMath Builder is a responsive React/TypeScript frontend with light/dark themes,
live [AsciiMath](https://asciimath.org/) and HTML/CSS previews, ordered external stylesheet references,
and explicit server image generation. Compose builds and serves it at the API
origin (`http://localhost:5000` by default). Node.js is used at build time only.

For Eclipse development, start the renderer and API in Eclipse, then run
`./scripts/start-ui.ps1` from the repository root. Open `http://127.0.0.1:5173`.
Vite reloads UI edits and proxies requests to `http://localhost:8086`; no Docker
or frontend rebuild is required. Use `-ApiOrigin http://localhost:8090` to change
the API origin, or set it in `frontend/.env.local`.
For local JAR packaging, run
`npm run build` in `frontend` before the Maven build.
See [frontend setup and checks](frontend/README.md).

### Generated image caching

Both `/image` and `/img` cache successful SVG output in memory using Caffeine
and in a persistent database. Local/IDE launches default to embedded SQLite;
Docker defaults to PostgreSQL. Concurrent requests for the same image in one API
process share one generation. Errors are not cached. Database entries survive
service restarts; the memory cache warms on demand.

```yaml
permmath:
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
Entries expire after that duration without access, in both layers. Successful
memory hits synchronously refresh the database expiry too; restart does not grant
expired entries another TTL. Expired rows are never read, even before cleanup.
External stylesheets are fetched and sanitized during generation. Changes at the same URL become visible after eviction, expiry or restart.

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
Liquibase applies versioned YAML schema migrations before the cache starts,
adopting the previous JDBC schema without removing entries or resetting expiry.

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
[deployment configuration](docker/README.md) for configuration and database tests.

### Rendering

Chromium renders styled HTML with locally bundled MathJax 3.2.2 (SVG output).
It is the sole HTML rendering backend; no engine selection is required. All image endpoints return SVG. Styled HTML is embedded using SVG foreignObject; `/img` returns pure formula SVG.
`imageWidth` is the layout width in pixels (500 by default); image height follows the content.
The legacy `default-dpi` setting is retained for configuration compatibility but
browser rendering uses CSS pixels rather than that value.

The project is a Maven reactor with four modules:

| Module | Responsibility |
|---|---|
| `permmath-api` | HTTP endpoints, controlled CSS fetching, sanitization and caching |
| `render-contract` | Versioned Protocol Buffers and generated gRPC client/server types |
| `renderer-core` | Chromium/GraalJS rendering, admission and disposable-process supervision |
| `renderer-service` | Private gRPC endpoint, health service and deployment lifecycle |

Run `mvn verify` with JDK 25 to build and test all modules. Docker is the recommended way to run both services: `docker compose up --build -d`.

Java and XML use four spaces for indentation, as configured in `.editorconfig`.
Always use braces for Java `if`, `else` and loop bodies. Prefer streams, predicates
and method references for collection transformations and checks when they preserve
evaluation order, short-circuiting and exception handling. Keep explicit loops for
resource management, checked exceptions and indexed mutation. Use
`StringUtils.isNotBlank` for null-safe blank checks where Commons Lang is available.
The Maven build enables compiler lint warnings, excluding annotation-processor
claim checks for runtime framework annotations.

For Windows development, install Chromium with `./scripts/install-rendering.ps1`, then build runtime dependencies:

```powershell
mvn package dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/runtime-deps'
$env:RENDERER_CHROMIUM_SANDBOX = 'false' # Windows development
java --enable-native-access=ALL-UNNAMED -cp 'renderer-service/target/renderer-service-1.0.jar;renderer-service/target/runtime-deps/*' org.permmath.renderer.RendererServiceMain
```

In another terminal, start the API with local plaintext gRPC explicitly enabled:

```powershell
$env:PERMMATH_RENDERING_PLAINTEXT = 'true'
java --enable-native-access=ALL-UNNAMED -jar permmath-api/target/permmath-api-1.0.jar
```

The renderer binds to loopback port 9090. The API defaults to TLS for TCP; see [deployment configuration](docker/README.md) for mutual TLS and the network-isolated Docker Unix socket. Chromium is resolved only within `PLAYWRIGHT_BROWSERS_PATH` (default `.runtime/browsers`), not an installed Chrome/Edge browser. Playwright 1.59.0 selects the matching browser build.

Opt-in browser tests run with `mvn verify '-Drendering.tests=true'` after installation. The styled example matches the Builder and Swagger request at 500 pixels wide; comparison images go into module `target/rendering` directories. Normal tests do not launch Chromium.

Eclipse launch groups can start the renderer first, wait for standard output
matching `event=renderer_ready transport=tcp`, then launch the API with
`PERMMATH_RENDERING_PLAINTEXT=true`. The readiness marker is written and flushed
on stdout after the gRPC listener starts. After changing the renderer, stop the
existing launch group and launch it again so the marker is emitted by the new process.

Both HTML and formula rendering use gRPC with request IDs and deadlines. The renderer uses a bounded Java work queue; full admission returns HTTP 503 with Retry-After, and an expired deadline or queue wait returns 504. Each job uses fresh execution state. Content/output are bounded at 16 MiB. `RenderGateway` separates API generation from the transport, and `RenderJobExecutor` separates rendering from gRPC. A future RabbitMQ consumer can reuse the contract/core; new async endpoints and pending indicator images remain future work.

API work also uses immediate admission with no queue:

```yaml
permmath:
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
`permmath.rate-limit` (or the matching `PERMMATH_RATE_LIMIT_*` environment variables):

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
it actually exits; interruption still kills its disposable child JVM. Queue expiry
or queued cancellation does not restart the renderer or interrupt another job.

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
(`permmath.rendering.timeout`, default `45s`, including queue waiting; child execution defaults to 30 seconds). User HTML is sanitized with a presentation element/attribute allowlist. External and inline CSS are parsed with ph-css and filtered by a presentation property/function allowlist. CSS variables, responsive media rules, supports rules, cascade layers and nested styles are retained. External stylesheets retain their application order; inline CSS follows them. Submitted images, SVG, links, scripts, forms and embedded documents are removed.

The `/image` response type is `image/svg+xml` instead of
`image/jpeg`; existing compressed permalinks resolve to SVG without changing their
payload. HTML SVG exports use `foreignObject` with computed styles embedded,
and keep MathJax formulas as vector paths. They are intended for browsers;
SVG tools that do not support `foreignObject` should use `/img` for individual
formulas. Ordinary HTML text still uses the viewer's fonts.

Styled HTML export uses bundled html-to-image 1.11.13 through the small source
adapter in `tools/svg/export-styled-svg.js`. Node.js is needed only to rebuild
the checked-in bundle. The exporter copies computed styles and retains nested
MathJax SVG; browser network guards and API-side SVG validation still apply.

Handwritten renderer JavaScript lives in `tools/svg`: browser startup and guards
are named modules, and both renderers share the MathJax safety configuration.
Run `npm run check` there to lint, check formatting and verify the checked-in bundles.
The template contains only trusted script references. Java reads browser results
through `window.PermMathRenderer`; no Node.js process runs in the service.

Both renderers enable MathJax's safe extension. The API independently validates
SVG before caching or serving it, using secure JDK StAX XML parsing, OWASP URL
filtering and ph-css. The presentation policy preserves computed styles,
foreignObject, MathML, glyph paths, local fragment references and HTTPS links.
Active elements, unsafe links, external resources, DTDs and XML entities are
rejected; unsupported CSS declarations are removed. Output is limited to 16 MiB,
depth 256 and 100,000 elements. Both cache identities include the output-policy
version. `/image` and `/img` send sandbox CSP, `nosniff` and `no-referrer` headers.

External stylesheets are fetched by a controlled HTTP client, with HTTPS on port 443, normal TLS certificate/hostname verification, and public IP addresses only. The connection uses the validated DNS addresses, so it cannot re-resolve an unchecked address. Every redirect is validated; HTTP downgrades, credentials and private/loopback/link-local/reserved destinations are rejected. CSS imports use the same fetcher and resolve relative to the final stylesheet URL.

Limits are 8 top-level references, 16 fetched resources including imports, 3 redirects per resource, 3 import levels, 1 MiB per response, 4 MiB combined decoded CSS, and a shared 15-second fetch deadline. Responses must be `text/css`; compressed responses are rejected (requests ask for identity encoding). CSS resource URLs, external fonts, executable expressions and unknown at-rules are removed.

Browser contexts block service workers, WebSockets and all requests except the current document and bundled rendering scripts. Only one document load in the approved main frame is allowed. Reloads and redirects are denied, extra pages close, and any child frame terminates the render, including srcdoc and data frames. Before document scripts run, an initialization script disables workers, service-worker registration, WebRTC constructors, WebTransport, WebSocketStream and window.open. The asset server disables DNS prefetch hints. A restrictive CSP supplies another boundary. Browser channel tests exercise these guards independently of the sanitizer and CSP; they do not establish OS-level network isolation.

Docker Compose isolates the renderer in a separate container with no network access. The API fetcher has bounded admission but still needs independent deployment egress and client rate controls.

### Docker deployment

The Dockerfile builds separate API and renderer images. Compose adds Squid for
mandatory public-HTTPS stylesheet egress and nginx for ingress, placing the API on
an isolated internal network. It also applies private socket communication, renderer
network isolation, read-only filesystems, resource limits and Chromium sandbox
controls. See [container deployment](docker/README.md) for configuration and verification.

### Prerequisites

- JDK 25 (set `JAVA_HOME` to its installation directory)
- Maven

### Installation

1. Clone the repository:

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
        org.permmath.logging: DEBUG
        org.permmath.rendering.cache: DEBUG
```

## Input limits

All four endpoints reject invalid or oversized application payloads with HTTP 400.
Limits count UTF-8 bytes and preserve accepted content and whitespace:

| Input | Limit |
|---|---|
| `/img` plain formula | 256 KiB, nonblank |
| Descriptor HTML/content (`forumla`) | 4 MiB, nonblank |
| Inline stylesheet | 1 MiB |
| Stylesheet references | Eight, nonblank, 2 KiB each |
| Image width | 220–4000, when supplied |
| Decompressed/serialized descriptor JSON | 8 MiB |
| Compressed descriptor | 8 MiB + 16 KiB |
| Base64 descriptor | 11,206,656 characters |

The JSON decoder limits nesting to eight, tokens to 128, property names to 64
characters, string tokens to 4,194,304 characters and numbers to ten characters.
It rejects trailing JSON. Descriptor validation applies before image rendering
and cache identity generation. Malformed Base64, GZIP or JSON returns HTTP 400.
Decompression enforces one total expansion budget, including concatenated GZIP members.
Existing permalinks exceeding these limits are rejected. JSON escaping counts toward
the serialized JSON budget. Web-server/proxy URL limits may be lower and reject a
request before these application limits apply.

These limits bound individual inputs. API admission and deadlines also bound
concurrent preparation/fetching. jsoup's streaming parser bounds HTML construction;
ph-css tokenizes CSS before its recursive grammar is run. Normalized HTML and
MathJax's compiled trees/output are checked again. Application code does not lex
HTML, CSS characters or math delimiters.

| Complexity boundary | Cap |
| --- | --- |
| Submitted HTML | 20,000 elements, 60,000 nodes, depth 128, 64 attributes/element |
| Inline style attribute | 32 KiB |
| CSS per source | 20,000 rules/selectors, 100,000 declaration separators, 262,144 tokens |
| CSS structure | 32 block/bracket levels, 64 function levels, 4,096 selector/token characters |
| Combined stylesheets | 20,000 rules/selectors; selector count × HTML element count ≤ 100 million |
| Numeric CSS grid repeat | 1,024 repetitions |
| Math input | 2,048 expressions/document; 262,144 characters/expression; formula endpoint also caps UTF-8 at 256 KiB |
| Compiled MathJax tree | 100,000 nodes/document; depth 128 |
| TeX expansion | 10,000 substitutions; 262,144-character buffer |
| Rendered DOM / SVG | 100,000 nodes/elements; depth 256; 16 MiB SVG |
| Rendered viewport/content | 32,768 pixels per dimension; 64 million pixels total |

Geometry is checked before SVG cloning/export, including overflowing content and
individual element bounds. Formula SVG dimensions are checked before serialization;
the API independently checks root SVG dimensions before caching. MathJax `ex`/`em`
dimensions use the renderer's 8/16-pixel conversion. Policy versions in cache identities
prevent reuse of artifacts generated under the earlier limits. These are structural
budgets, not a prediction of layout cost: isolated workers retain their 30-second
deadline, 1 GiB heap and container CPU/memory limits for expensive accepted inputs.

All complexity defaults live in
[complexity-defaults.properties](render-contract/src/main/resources/complexity-defaults.properties).
Override a setting with a JVM option such as
`-Dpermmath.complexity.render-dimension=24000` or the environment variable
`PERMMATH_COMPLEXITY_RENDER_DIMENSION=24000`. JVM options take precedence over
environment variables. These are startup settings; use the same overrides in the
API and renderer services. For Compose, add them to both services' `environment`
sections; for Eclipse, use the corresponding VM arguments or Environment tabs.
They are not Spring `application.yaml` properties. Nonpositive/invalid values and
SVG budgets larger than the transport limit are rejected.

The renderer forwards its resolved policy to disposable JVMs and injects the same
values into Chromium and GraalJS before their bundles run. JavaScript contains no
duplicate defaults. The resolved policy fingerprint participates in cache keys.
MathJax supplies expression discovery and native TeX expansion limits; [AsciiMath](https://asciimath.org/)
has no native pre-parse nesting cap. Its byte/character budget, compiled-tree checks
and isolated worker deadline/memory limits remain necessary. Parser errors and
exceeded math budgets reject the render instead of exporting an error image.
