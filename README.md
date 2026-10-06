# MathRenderAPI

MathRenderAPI generates SVG images from compressed formula and styled HTML descriptors. Its permalink generator accepts ASCIIMath or HTML and optional CSS, then stores the descriptor in a compressed URL payload.

## Features
- Generate images from ASCIIMath-encoded formulas
- Customize formula appearance with CSS
- Create permalinks for any formula
- Generate images from previously created permalinks

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /url?formula=...` | Generate a compressed permalink. Optional `imageWidth`, `style`, and repeated external HTTPS `styleRefs` parameters. |
| `GET /image?ubcq=...` | Render compressed styled HTML with backtick-delimited ASCIIMath as SVG. |
| `GET /img?formula=...` | Render an uncompressed plain ASCIIMath formula as standalone SVG, without a browser. |
| `GET /decode?ubcq=...` | Decode a compressed payload into its JSON descriptor. |

`/image` and `/decode` use `ubcq`: a GZIP-compressed UTF-8 JSON image descriptor,
Base64-encoded and URL-encoded. `/url` always generates this format, including
for short formulas. The `compressUrlQuery` option and uncompressed input modes
have been removed. `/image` and `/decode` require `ubcq`; the former
`/image-cq` and `/decode-cq` route names are no longer available.

### Formula-only SVG: `/img`

Send plain ASCIIMath directly, without HTML or backtick delimiters:

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
payloads for `/image` and `/decode`.

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

## Getting Started for Developers

### Generated image caching

Both `/image` and `/img` cache successful SVG output in memory using Caffeine
(version managed by Spring Boot). Concurrent requests for the same image share
one generation. Errors are not cached. Entries disappear on service restart.

```yaml
permmath:
    image-cache:
        maximum-size: 1GB
        expire-after-access: 60d
```

The memory budget uses an estimate of SVG string storage, keys and entry overhead;
it is not a hard limit on total JVM memory or in-flight rendering. Caffeine evicts
entries to stay within the budget. Images larger than the budget are returned but
not retained. Each cache hit resets the full configured TTL (60 days by default).
Entries expire after that duration without access.
External stylesheets are fetched and sanitized during generation. Changes at the same URL become visible after eviction, expiry or restart.

Keys are SHA-256 hashes of a versioned rendering identity and the effective
descriptor, independent of the compressed URL spelling. HTML keys include the
resolved width. Formula-only keys depend on the exact formula text.
The HTML cache identity includes the security policy version. Bump the rendering identity version in
`GeneratedImageService` when a template, MathJax bundle, browser version or export
change affects output before introducing persistent storage.

`GeneratedImageStore` separates output storage from rendering and HTTP responses.
It currently has a Caffeine implementation; a later implementation can add durable
storage under the same stable keys, with memory as a front cache. No disk/database
storage is enabled yet. Hit, miss, load and eviction counters are available through
`CaffeineImageStore.stats()`; there is no public cache-management endpoint.

### Rendering

Chromium renders styled HTML with locally bundled MathJax 3.2.2 (SVG output).
It is the sole HTML rendering backend; no engine selection is required. All image endpoints return SVG. Styled HTML is embedded using SVG foreignObject; `/img` returns pure formula SVG.
`imageWidth` is the layout width in pixels; image height follows the content.
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

Opt-in browser tests run with `mvn verify '-Drendering.tests=true'` after installation. The styled example fetches Bootstrap from its public HTTPS CDN; comparison images go into module `target/rendering` directories. Normal tests do not launch Chromium.

Both HTML and formula rendering use gRPC with request IDs and deadlines. Occupied renderer capacity returns HTTP 503; a deadline returns 504. Each job uses fresh execution state. Content/output are bounded at 8 MiB. `RenderGateway` separates API generation from the transport, and `RenderJobExecutor` separates rendering from gRPC. A future RabbitMQ consumer can reuse the contract/core; new async endpoints and pending indicator images remain future work.

Rendering waits for MathJax, fonts, and images, with a configurable timeout
(`permmath.rendering.timeout`, default `30s`). User HTML is sanitized with a presentation element/attribute allowlist. External and inline CSS are parsed with ph-css and filtered by a presentation property/function allowlist. CSS variables, responsive media rules, supports rules, cascade layers and nested styles are retained. External stylesheets retain their application order; inline CSS follows them. Submitted images, SVG, links, scripts, forms and embedded documents are removed.

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

Both renderers enable MathJax's safe extension. The API independently validates
SVG before caching or serving it, using secure JDK StAX XML parsing, OWASP URL
filtering and ph-css. The presentation policy preserves computed styles,
foreignObject, MathML, glyph paths, local fragment references and HTTPS links.
Active elements, unsafe links, external resources, DTDs and XML entities are
rejected; unsupported CSS declarations are removed. Output is limited to 8 MiB,
depth 128 and 20,000 elements. Both cache identities include the output-policy
version. `/image` and `/img` send sandbox CSP, `nosniff` and `no-referrer` headers.

External stylesheets are fetched by a controlled HTTP client, with HTTPS on port 443, normal TLS certificate/hostname verification, and public IP addresses only. The connection uses the validated DNS addresses, so it cannot re-resolve an unchecked address. Every redirect is validated; HTTP downgrades, credentials and private/loopback/link-local/reserved destinations are rejected. CSS imports use the same fetcher and resolve relative to the final stylesheet URL.

Limits are 8 top-level references, 16 fetched resources including imports, 3 redirects per resource, 3 import levels, 1 MiB per response, 4 MiB combined decoded CSS, and a shared 15-second fetch deadline. Responses must be `text/css`; compressed responses are rejected (requests ask for identity encoding). CSS resource URLs, external fonts, executable expressions and unknown at-rules are removed.

Browser contexts block service workers, WebSockets and all requests except the current document and bundled rendering scripts. Only one document load in the approved main frame is allowed. Reloads and redirects are denied, extra pages close, and any child frame terminates the render, including srcdoc and data frames. Before document scripts run, an initialization script disables workers, service-worker registration, WebRTC constructors, WebTransport, WebSocketStream and window.open. The asset server disables DNS prefetch hints. A restrictive CSP supplies another boundary. Browser channel tests exercise these guards independently of the sanitizer and CSP; they do not establish OS-level network isolation.

Docker Compose isolates the renderer in a separate container with no network access. The API fetcher still needs deployment egress and admission controls.

### Docker deployment

The Dockerfile builds separate API and renderer images. Compose applies private socket communication, renderer network isolation, read-only filesystems, resource limits and Chromium sandbox controls. See [container deployment](docker/README.md) for configuration and verification.

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
