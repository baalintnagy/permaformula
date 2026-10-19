# HTTP API

Commands and source paths below are relative to the repository root.

## Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /ubcq?formula=...` | Generate a raw Base64 compressed descriptor. Optional `imageWidth`, `style`, and repeated external HTTPS `styleRefs` parameters. |
| `GET /ubcq-decode?ubcq=...` | Decode a descriptor to JSON containing original HTML (`forumla`), CSS (`style`), `styleRefs`, and optional `imageWidth`, without rendering. |
| `GET /html-svg?ubcq=...` | Render compressed styled HTML with backtick-delimited [AsciiMath](https://asciimath.org/) as SVG. |
| `GET /exp-jpg?formula=...` | Convert the corresponding cached formula SVG to high quality JPEG. |
| `GET /html-jpg?ubcq=...` | Convert the corresponding cached styled HTML SVG to high quality JPEG. |
| `GET /exp-svg?formula=...` | Render an uncompressed plain [AsciiMath](https://asciimath.org/) formula as standalone SVG, without a browser. |

`/html-svg` uses `ubcq`: a GZIP-compressed UTF-8 JSON image descriptor,
Base64-encoded and URL-encoded in image URLs. `/ubcq` returns raw Base64, including
for short formulas. The `compressUrlQuery` option and uncompressed input modes
have been removed. `/html-svg` requires `ubcq`. The public `/decode` endpoint and
the former `/image-cq` and `/decode-cq` route names are no longer available.

The builder's **Decode UBCQ** section accepts a raw descriptor or a URL containing
the `ubcq` query parameter. **Decode and edit** restores the composer sources and
canvas width, updates its live preview, scrolls to it, and highlights the editors
and preview. Optional settings omitted from a descriptor reset to empty CSS,
no stylesheet references, and a 500px canvas.

The SVG endpoints are `/exp-svg` and `/html-svg`. The former `/exp`, `/html`,
`/img`, and `/image` routes return 404; update clients and stored image URLs.

### JPEG images: `/exp-jpg` and `/html-jpg`

The JPEG endpoints accept exactly the parameters of their SVG counterparts:
`formula` for `/exp-jpg`, and compressed `ubcq` for `/html-jpg`. They return
`image/jpeg`. Calling a JPEG endpoint first generates, validates and caches the
corresponding SVG using the existing SVG identity. The isolated Chromium renderer
then rasterizes that SVG, including HTML `foreignObject` content, at up to twice
its CSS dimensions with 95% JPEG quality. Transparent areas become white.
Resolution is reduced toward 1x when necessary to respect the shared dimension
and pixel-area limits. SVG and JPEG failures are never cached; a failed conversion
retains a successfully generated SVG for retry.

Both formats share the bounded memory and persistent cache budgets, TTL and
single-generation behavior. JPEG cache identities include the validated source
SVG, conversion settings and complexity policy. For schema compatibility, JPEGs
are stored as tagged Base64 text in the existing payload column; persistent byte
accounting includes this encoding overhead. Stored JPEG metadata is revalidated
before reuse. A JPEG request refreshes its source SVG as well as the JPEG expiry.
A new conversion consumes a separate generation allowance after any SVG miss;
configure quotas with this additional work in mind. API and renderer must be
updated together. Both ingress profiles protect the new routes in the same way
as their SVG counterparts. The Builder renders SVG previews and provides SVG/JPG URLs with separate Copy buttons and Open links.

### Compressed descriptor: `/ubcq`

`GET /ubcq?formula=...` accepts the former `/url` parameters: HTML content in
`formula`, optional `imageWidth`, optional CSS in `style`, and repeated HTTPS
`styleRefs`. It returns only the GZIP-compressed, Base64-encoded JSON descriptor
as `text/plain;charset=UTF-8`, with no hostname, port, path or `ubcq=` prefix.
URL-encode the returned value once when building `/html-svg?ubcq=...` or
`/html-jpg?ubcq=...`. Swagger accepts the raw value and encodes it for you.
The Builder constructs complete image URLs using its API origin (or configured
API origin in development); separate-origin deployments redirect them to the
image listener. Copy and Open SVG continue to use complete working URLs.

The former `/url` and `/url-encode` routes have been removed and return 404.
This operation performs no rendering. Descriptor preparation remains bounded by
the existing input limits, request deadline, admission and request quotas; its
responses use no-store, nosniff and no-referrer headers.

### Decode a descriptor: `/ubcq-decode`

Paste the raw `/ubcq` response into the Swagger `ubcq` field. For a URL, extract
its `ubcq` query value first; Swagger performs the required URL encoding.
This runnable example is shared with `/html-svg` and `/html-jpg` in Swagger:

```sh
curl --get --data-urlencode 'ubcq=H4sIAAAAAAACCqtWSssvKs3NSVSyUkqoiDNKUNJRysxNTE8Nz0wpyVCyMjIyqAUAxqPtbyQAAAA=' http://localhost:8086/ubcq-decode
```

Response: `200 OK`, `Content-Type: application/json`, `Cache-Control: no-store`:

```json
{"forumla":"`x^2`","imageWidth":220}
```

`forumla` is the historical spelling of the original HTML/formula field. `style`
contains original CSS and `styleRefs` contains external HTTPS stylesheet URLs in
application order. Missing or empty optional fields are omitted; an omitted
`imageWidth` remains omitted rather than being replaced with the render default.
Decoding validates the payload and input limits but does not sanitize sources,
render an image, or fetch external stylesheets.

For JPEG, reuse the same formula or descriptor:

```sh
curl --get --data-urlencode 'formula=sqrt(x^2 + y^2)' http://localhost:8086/exp-jpg --output formula.jpg
curl --get --data-urlencode 'ubcq=H4sIAAAAAAACCqtWSssvKs3NSVSyUkqoiDNKUNJRysxNTE8Nz0wpyVCyMjIyqAUAxqPtbyQAAAA=' http://localhost:8086/html-jpg --output formula-html.jpg
```

Swagger documents successful response types and common `application/problem+json`
errors: `400` for invalid input, `429` for client quotas (with `Retry-After`),
`503` for unavailable capacity, and `504` when the request deadline expires.
In separate-origin deployments, image requests to the API origin redirect to the
configured image origin; use `curl --location` to follow those redirects.

### Formula-only SVG: `/exp-svg`

Send plain [AsciiMath](https://asciimath.org/) directly, without HTML or backtick delimiters:

```sh
curl --get --data-urlencode 'formula=sqrt(x^2 + y^2)' http://localhost:8086/exp-svg --output formula.svg
```

`/exp-svg` accepts only a required `formula` parameter. The formula determines its
natural dimensions; there are no width or styling options. The former compressed
`ubcq` input is no longer supported by this endpoint. `/ubcq` generates
compressed descriptors for `/html-svg` and `/html-jpg`.

Response: `200 OK`, `Content-Type: image/svg+xml`. The standalone formula SVG
contains embedded glyph paths and can be used as an HTML image source. Missing
or blank formulas return `400 Bad Request`. Query parameters must still be
URL-encoded, including literal `+` characters.

Formula, HTML, CSS and stylesheet references are preserved without whitespace
normalization. The historical `forumla` JSON spelling remains in compressed
payloads for `/html-svg`.

This endpoint runs bundled MathJax 3.2.2 with its lightweight DOM adapter in
GraalJS in a reusable isolated JVM inside the separate renderer service. It launches no browser and needs no
Node.js installation at runtime. Build instructions are in
[`tools/svg/README.md`](../tools/svg/README.md).

## Usage

### Error responses

Exception handling is provided by
[`local-exception-handler`](https://github.com/baalintnagy/local-exception-handler)
(`io.github.baalintnagy:local-exception-handler:0.9.4`).
Handled errors return `application/problem+json` with standard Problem Detail
fields (`type`, `title`, `status`, `detail`, `instance`), plus an `id` and
`timestamp`. Jakarta, request-body and MVC method validation errors include
`violations` with `property`, `code`, and `message`. Submitted values are omitted.
Illegal arguments include `errorType: BUSINESS_RULE_VIOLATION`. Spring's original
error details, statuses, headers and extensions are retained. The handler logs
exception type and correlation metadata without exception messages or stack traces.
Otherwise unhandled MVC exceptions return a generic 500 Problem Detail without
exposing exception messages.

This replaces the previous custom error JSON format and `permaformula.exception`
configuration. The service uses Spring Boot 4 / Spring Framework 7, as required
by the exception-handler library.

Simply send a GET request to the relevant endpoint with your desired parameters.

Generate a descriptor with `/ubcq`, URL-encode it in `/html-svg?ubcq=...`, then request that URL to render it.

The default production deployment uses one hostname for the Builder UI, API and
images. Configure `PERMAFORMULA_HOST` to its public HTTPS origin (a provider-assigned
hostname is sufficient). Base Compose exposes nginx on `127.0.0.1:5000` for a local
proxy.
`/html-svg` and `/exp-svg` still pass through credential-stripping
image ingress, mandatory trusted-ingress validation, sanitized SVG output and a
restrictive CSP sandbox. Keep the API and renderer private.

The previous two-origin deployment is preserved as `separate-origins` in
`compose.separate-origins.yaml`. It additionally isolates image cookies using
separate registrable domains. See [deployment configuration](../docker/README.md)
for both profiles, security properties and validation.
