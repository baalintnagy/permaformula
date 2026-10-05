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
| `GET /img?ubcq=...` | Render the compressed descriptor's plain ASCIIMath formula as standalone SVG, without a browser. |
| `GET /decode?ubcq=...` | Decode a compressed payload into its JSON descriptor. |

All rendering requests use `ubcq`: a GZIP-compressed UTF-8 JSON image descriptor,
Base64-encoded and URL-encoded. `/url` always generates this format, including
for short formulas. The `compressUrlQuery` option and uncompressed input modes
have been removed. `/image` and `/decode` require `ubcq`; the former
`/image-cq` and `/decode-cq` route names are no longer available.

### Formula-only SVG: `/img`

Generate a payload using `/url`, then use the returned query string with `/img`
instead of `/image`. Supply plain ASCIIMath without HTML or backtick delimiters:

```sh
link=$(curl --get --data-urlencode 'formula=sqrt(x^2 + y^2)' http://localhost:8086/url)
curl "http://localhost:8086/img?${link#*\?}" --output formula.svg
```

The substitution above uses Bash. The `ubcq` query parameter is identical for both
rendering endpoints. `/img` uses only the descriptor's `forumla` field; width and
style options apply to `/image`. The expression determines its natural dimensions.
The historical `forumla` JSON spelling is retained for compressed payload compatibility.

Response: `200 OK`, `Content-Type: image/svg+xml`. The standalone formula SVG
contains embedded glyph paths and can be used as an HTML image source. Missing
payloads, invalid compression, or blank formulas return `400 Bad Request`.

This endpoint runs bundled MathJax 3.2.2 with its lightweight DOM adapter in
GraalJS inside the JVM. It launches neither Chromium nor JavaFX and needs no
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

### Rendering

Both backends render styled HTML with locally bundled MathJax 3.2.2 (SVG output).
The default is JavaFX; select Chromium with `permmath.rendering.engine=chromium`
(or `PERMMATH_RENDERING_ENGINE=chromium`). All image endpoints return SVG. Styled HTML is embedded using SVG foreignObject; `/img` returns pure formula SVG.
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

Browser tests require the local download and a graphical environment for JavaFX.
They write comparison images to `target/rendering`. Normal unit tests do not
launch a browser. JavaFX WebView uses an invisible native window and the FX
application thread. Its packaged classpath deployment currently emits JavaFX's
unnamed-module warning. Linux needs a display or Xvfb; the Dockerfile installs
Linux Chromium and uses Xvfb so either backend can be selected.

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
