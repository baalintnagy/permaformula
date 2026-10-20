# PermaFormula Builder

Static React/TypeScript UI for the existing Java API. Node.js 24 is used only for development and builds. There is no frontend runtime server in production.

Live previews use the renderer's locally bundled MathJax 4.1.3, including its
integrated AsciiMath input and New Computer Modern SVG glyphs. No CDN is used.
Rebuild `tools/svg` after changing MathJax dependencies, then rebuild the frontend
to copy the matching browser components and font chunks.

## Development

The development runtime connects to the Java services launched in Eclipse. It runs independently of Docker and reloads frontend changes immediately.

Shared Eclipse launch configurations and a Cloudflare development tunnel launcher
are available in [eclipse/README.md](../eclipse/README.md).

The builder serves Open Graph and large-image social card metadata in its initial
HTML, with a bundled 2400Ã—1260 JPEG preview (`public/assets/og.jpg`). Development canonical/image URLs use
`PERMAFORMULA_DEV_UI_ORIGIN` (set by the tunnel launcher); packaged deployments use the
backend's `PERMAFORMULA_HOST` at runtime, so the frontend needs no domain-specific build.

1. Start `RendererServiceMain` in Eclipse with working directory at the repository root and `RENDERER_CHROMIUM_SANDBOX=false` on Windows. It listens on `localhost:9090`.
2. Start `PermaFormulaApplication` in Eclipse with `PERMAFORMULA_RENDERING_PLAINTEXT=true`. Its API listens on `localhost:8086`. Leave `PERMAFORMULA_IMAGE_HOST` unset for this single-origin local setup.
3. From the repository root, start the UI:

```bash
bash scripts/local/start-ui.sh
```

Open `http://127.0.0.1:5173`. The launcher starts only the frontend; Eclipse continues to own both Java processes. API restarts do not require restarting Vite. Restart Vite after changing the API origin configuration.

To use a different Eclipse API port:

```bash
bash scripts/local/start-ui.sh --api-origin http://localhost:8090
```

Alternatively copy `frontend/.env.example` to `frontend/.env.local` and edit `PERMAFORMULA_DEV_API`. The local file is ignored by Git. An environment variable or the launcher's `--api-origin` takes precedence over that file.

You can also start Vite directly:

```bash
cd frontend
npm ci
npm run dev
```

The development proxy forwards image and permalink requests, Swagger UI, and OpenAPI to the API. Requests use the proxy to avoid development CORS issues; copied formula URLs point directly at the Java API so they keep working when Vite stops. `/ubcq` returns raw Base64 descriptors; the UI constructs complete HTML image URLs using the same API origin as formula URLs, encoding each descriptor once. Local MathJax previews work even while the Java services are stopped; server rendering requires both services.

To use Compose instead:

```bash
export PERMAFORMULA_DEV_API='http://localhost:5000'
npm run dev
```

## Production

`docker compose up --build -d` builds the frontend and packages it in the API JAR. Open the API origin, `http://localhost:5000` by default. The image listener continues to expose only `/exp-svg`, `/html-svg`, `/exp-jpg`, and `/html-jpg`.

For a local Java build, first run `npm ci` and `npm run build` in this directory, then run `mvn verify` from the repository root. The API module copies `frontend/dist` into its static resources. Rebuild the frontend before packaging subsequent UI changes. A Java-only build without `frontend/dist` remains supported but does not include the UI.

The asset copy is a Maven `copy-resources` execution, skipped by Eclipse's incremental builder. The sibling `frontend/dist` directory is not registered as an Eclipse resource/source folder. Eclipse development uses Vite; command-line Maven builds and Docker still include the static UI. After changing Maven configuration, select **Maven â†’ Update Projectâ€¦** in Eclipse (`Alt+F5`).

The **Render** buttons display server-rendered SVG previews. Both workspaces offer SVG and JPG URL rows, separate Copy buttons, and Open SVG/Open JPG links that open in a new tab. Expression URLs follow the current formula; composition URLs retain the last rendered input and mark it when the source changes.

## Checks

Run `npm run test:browser:install` before `npm run test:browser`. The wrapper uses
the browser revision required by the installed frontend Playwright in
`.runtime/frontend-browsers`, separately from Java Playwright's `.runtime/browsers`.
This prevents browser installs from pruning the other runtime's browser. Custom
`PLAYWRIGHT_BROWSERS_PATH` and `PERMAFORMULA_TEST_CHROMIUM` overrides remain supported;
an executable override must match the installed frontend Playwright version.

```bash
npm run build
npm test
npm run test:browser
```

Browser checks require Playwright Chromium (use `npm run test:browser:install` if it is not installed). The browser suite starts Vite and mocks image API responses; it validates real local MathJax rendering, API request encoding, tab navigation, iframe isolation, theme persistence, and mobile layout.

After building the frontend, `mvn -o -pl permaformula-api -am '-Dtest=BuilderUiTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dui.tests=true' test` runs an additional integration suite against a real Spring Boot server with the existing test rendering gateway. It checks packaged asset delivery and both real SVG generation flows. This requires the repository's Chromium installation (`scripts/local/install-rendering.sh`).

## Preview and delivery

- Formula URLs are encoded exactly once by `URLSearchParams`. `/url-encode` is never called.
- MathJax 4.1.3 assets are copied from the renderer's checked-in distribution during dev/build, with its license and local SVG font chunks. The checked-in distribution also includes provenance. No CDN or duplicate math engine dependency.
- User HTML is sanitized with DOMPurify, then placed in an opaque-origin iframe (`sandbox="allow-scripts"`, without `allow-same-origin`). Only nonce-authorized preview code and bundled MathJax can execute. External HTTPS CSS/fonts can load; images, scripts supplied by the user, forms, navigation, and API requests are blocked.
- CSS is assigned as text to a stylesheet, never interpolated into HTML. App theme changes do not alter the white image canvas.
- External stylesheets and fonts wait for **Load styles** before the live preview downloads them. The control hides after clicking and returns when stylesheet references or external CSS imports/font URLs change. Its hint explains that third-party hosts can observe the browser's IP address and request timing. The Java API fetches and validates stylesheets separately when rendering; the generated server image is authoritative.
- `/exp-svg` is fetched anonymously on user action and displayed as an image blob. Render calls `/ubcq`, then builds and fetches the `/html-svg` URL anonymously into a sandboxed viewer. A brief highlight signals completion; reduced-motion users receive a static outline. The Open image link supports Ctrl-click or middle-click to open a background tab.
- Inputs are not saved automatically. Only the theme preference is persisted.

# Cache administration

Open `/admin.html` and sign in as `admin` to access the separate list with per-entry Delete buttons. Supply `PERMAFORMULA_ADMIN_PASSWORD` to the API as an environment secret. Docker Compose passes this variable from the deployment environment; do not commit its value. With no password set, admin login is disabled. Spring Security verifies the password using BCrypt and issues a signed JWT valid for 15 minutes. The JWT stays in page memory and is sent in the Authorization header. Sign out clears it from the page. Deploy over HTTPS.

JWT signing uses a fresh RSA key on each API startup; restarting the API invalidates existing logins. With multiple API instances, use sticky routing for admin requests or configure shared signing keys before scaling. Passwords are limited to 72 UTF-8 bytes by BCrypt.

Deletion removes the selected persisted entry and the serving process's memory copy. Rendering the same source later can recreate it; this is cache eviction, not a content ban. The admin list shows the same live source-bearing SVG entries as the public Library. Metadata-free entries and JPEG conversions are not listed. In deployments with multiple API instances, other instances may retain memory copies until expiry.

Simple-expression image URLs use `formula`, never `ubcq`. Library formula entries recover
their original formula from the supplied descriptor (or locally decode its stored GZIP token)
and build the same public expression URLs. HTML entries continue using UBCQ. Public and admin
previews/downloads share this behavior and require no link-preparation API request.
