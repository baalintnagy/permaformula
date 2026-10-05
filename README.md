# MathRenderAPI

MathRenderAPI is a RESTful web service that generates images for mathematical formulas, provided as URL-encoded ASCIIMath input and optional URL-encoded CSS for styling. It also offers a permalink feature, allowing formulas to be shared and referenced easily.

## Features
- Generate images from ASCIIMath-encoded formulas
- Customize formula appearance with CSS
- Create permalinks for any formula
- Generate images from previously created permalinks

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /img?formula=...` | Render a single plain ASCIIMath expression as standalone SVG, without a browser. |
| `GET /image?formula=...` | Render styled HTML containing backtick-delimited ASCIIMath as JPEG. Optional `imageWidth`, `style`, and repeated `styleRefs` parameters. |
| `GET /url?formula=...` | Return an image permalink. Accepts the same options as `/image`; `compressUrlQuery=true` produces a compressed permalink. |
| `GET /image-cq?ubcq=...` | Render a compressed image descriptor as JPEG. |
| `GET /decode?url=...` | URL-decode an uncompressed permalink's query string. |
| `GET /decode-cq?ubcq=...` | Decode a compressed payload into its JSON descriptor. |

### Formula-only SVG: `/img`

The required `formula` parameter is a **plain ASCIIMath expression**, without
HTML or backtick delimiters. For example:

```text
http://localhost:8086/img?formula=a%5E2%20%2B%20b%5E2%20%3D%20c%5E2
```

Or let curl encode the formula:

```sh
curl --get --data-urlencode 'formula=sum_(i=1)^n i^3=((n(n+1))/2)^2' \
  http://localhost:8086/img --output formula.svg
```

Response: `200 OK`, `Content-Type: image/svg+xml`, containing a standalone SVG
with embedded glyph paths. It scales without rasterization and requires no
external MathJax script or font files when displayed. It can be used directly
as an HTML image source:

```html
<img src="http://localhost:8086/img?formula=sqrt(x%5E2%20%2B%20y%5E2)" alt="Square root of x squared plus y squared">
```

Missing or blank formulas return `400 Bad Request` with `application/problem+json`.
Always URL-encode the formula, especially a literal `+` (encode it as `%2B`).
`/img` does not accept HTML layout, external stylesheets, or the JPEG width
options. The expression determines the SVG's natural dimensions.

This endpoint runs bundled MathJax 3.2.2 with its lightweight DOM adapter in
GraalJS inside the JVM. It launches neither Chromium nor JavaFX and needs no
Node.js installation at runtime. The JavaScript build instructions are in
[`tools/svg/README.md`](tools/svg/README.md). The HTML/JPEG endpoints still use
the configured browser renderer.

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

For example, to render an image of the formula "a^2 + b^2 = c^2", first URL-encode the formula (resulting in "a%5E2+%2B+b%5E2+%3D+c%5E2"), then send a GET request to:

`http://your-domain-name.com/img?formula=a%5E2+%2B+b%5E2+%3D+c%5E2`

## Getting Started for Developers

### Rendering

Both backends render styled HTML with locally bundled MathJax 3.2.2 (SVG output).
The default is Chromium; select JavaFX with `permmath.rendering.engine=javafx`
(or `PERMMATH_RENDERING_ENGINE=javafx`). Existing image endpoints return JPEG.
`imageWidth` is the layout width in pixels; image height follows the content.
The legacy `default-dpi` setting is retained for configuration compatibility but
browser rendering uses CSS pixels rather than that value.

Install the dedicated Chromium build on Windows:

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

### Prerequisites

- JDK 25 (set `JAVA_HOME` to its installation directory)
- Maven

### Installation

1. Clone the repository:
