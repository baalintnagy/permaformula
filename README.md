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
| `GET /url?formula=...` | Generate a compressed permalink. Optional `imageWidth`, `style`, and repeated `styleRefs` parameters. |
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
GraalJS inside the JVM. It launches no browser and needs no
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
        expire-after-write: 60d
```

The memory budget uses an estimate of SVG string storage, keys and entry overhead;
it is not a hard limit on total JVM memory or in-flight rendering. Caffeine evicts
entries to stay within the budget. Images larger than the budget are returned but
not retained. Expiry is measured from generation, so repeated reads do not extend it.
External CSS and image resources are captured during generation; changes to those
resources become visible after eviction, expiry or restart.

Keys are SHA-256 hashes of a versioned rendering identity and the effective
descriptor, independent of the compressed URL spelling. HTML keys include the
resolved width. Formula-only keys depend on the exact formula text.
Stylesheet order is preserved. Bump the rendering identity version in
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

To use Chromium, install the dedicated build on Windows:

```powershell
$env:JAVA_HOME = 'C:\Java\jdk25'
./scripts/install-rendering.ps1
```

The installer uses Playwright 1.58.0's matching Chromium build and stores it under
`.runtime/browsers` in this project. Rendering resolves that version's executable
inside the configured `permmath.rendering.browser-directory`; it does not select
an installed Chrome or Edge browser. The runtime directory is excluded from Git
and Docker build context. Chromium uses a fresh browser context for each request.

```powershell
mvn verify -Drendering.tests=true
java --enable-native-access=ALL-UNNAMED -jar target/permmath-service-1.0.jar
```

Browser tests require the local Chromium download and run headlessly.
They write comparison images to `target/rendering`. Normal unit tests do not
launch a browser. The Dockerfile installs Linux Chromium and its dependencies;
no graphical display or virtual framebuffer is required.

Rendering waits for MathJax, fonts, and images, with a configurable timeout
(`permmath.rendering.timeout`, default `30s`). User HTML and external stylesheets
are currently accepted, as in the original design.

The `/image` response type is `image/svg+xml` instead of
`image/jpeg`; existing compressed permalinks resolve to SVG without changing their
payload. HTML SVG exports use `foreignObject` with computed styles embedded,
and keep MathJax formulas as vector paths. They are intended for browsers;
SVG tools that do not support `foreignObject` should use `/img` for individual
formulas. Ordinary HTML text still uses the viewer's fonts. Referenced images
and CSS background images are embedded as data URLs and must permit fetching
from the render document's origin (CORS); a fetch failure fails the export.

### Prerequisites

- JDK 25 (set `JAVA_HOME` to its installation directory)
- Maven

### Installation

1. Clone the repository:
