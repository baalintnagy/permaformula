# PermMath Studio

Static React/TypeScript UI for the existing Java API. Node.js 24 is used only for development and builds. There is no frontend runtime server in production.

## Development

The development runtime connects to the Java services launched in Eclipse. It runs independently of Docker and reloads frontend changes immediately.

1. Start `RendererServiceMain` in Eclipse with working directory at the repository root and `RENDERER_CHROMIUM_SANDBOX=false` on Windows. It listens on `localhost:9090`.
2. Start `PermMathApplication` in Eclipse with `PERMMATH_RENDERING_PLAINTEXT=true`. Its API listens on `localhost:8086`. Leave `PERMMATH_IMAGE_HOST` unset for this single-origin local setup.
3. From the repository root, start the UI:

```powershell
./scripts/start-ui.ps1
```

Open `http://127.0.0.1:5173`. The launcher starts only the frontend; Eclipse continues to own both Java processes. API restarts do not require restarting Vite. Restart Vite after changing the API origin configuration.

To use a different Eclipse API port:

```powershell
./scripts/start-ui.ps1 -ApiOrigin http://localhost:8090
```

Alternatively copy `frontend/.env.example` to `frontend/.env.local` and edit `PERMMATH_DEV_API`. The local file is ignored by Git. An environment variable or the launcher's `-ApiOrigin` takes precedence over that file.

You can also start Vite directly:

```powershell
cd frontend
npm ci
npm run dev
```

The development proxy forwards image and permalink requests, Swagger UI, and OpenAPI to the API. Requests use the proxy to avoid development CORS issues; copied formula URLs point directly at the Java API so they keep working when Vite stops. `/url` permalinks retain the origin configured by the Java application. Local MathJax previews work even while the Java services are stopped; server rendering requires both services.

To use Compose instead:

```powershell
$env:PERMMATH_DEV_API = 'http://localhost:5000'
npm run dev
```

## Production

`docker compose up --build -d` builds the frontend and packages it in the API JAR. Open the API origin, `http://localhost:5000` by default. The image listener continues to expose only `/img` and `/image`.

For a local Java build, first run `npm ci` and `npm run build` in this directory, then run `mvn verify` from the repository root. The API module copies `frontend/dist` into its static resources. Rebuild the frontend before packaging subsequent UI changes. A Java-only build without `frontend/dist` remains supported but does not include the UI.

The asset copy is a Maven `copy-resources` execution, skipped by Eclipse's incremental builder. The sibling `frontend/dist` directory is not registered as an Eclipse resource/source folder. Eclipse development uses Vite; command-line Maven builds and Docker still include the static UI. After changing Maven configuration, select **Maven → Update Project…** in Eclipse (`Alt+F5`).

## Checks

```powershell
npm run build
npm test
npm run test:browser
```

Browser checks require Playwright Chromium (use `npx playwright install chromium` if it is not installed). The browser suite starts Vite and mocks image API responses; it validates real local MathJax rendering, API request encoding, tab navigation, iframe isolation, theme persistence, and mobile layout.

After building the frontend, `mvn -o -pl permmath-api -am '-Dtest=StudioUiTests' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dui.tests=true' test` runs an additional integration suite against a real Spring Boot server with the existing test rendering gateway. It checks packaged asset delivery and both real SVG generation flows. This requires the repository's Chromium installation (`scripts/install-rendering.ps1`).

## Preview and delivery

- Formula URLs are encoded exactly once by `URLSearchParams`. `/url-encode` is never called.
- MathJax 3.2.2 assets are copied from the renderer's checked-in distribution during dev/build, with its license and provenance. No CDN or duplicate math engine dependency.
- User HTML is sanitized with DOMPurify, then placed in an opaque-origin iframe (`sandbox="allow-scripts"`, without `allow-same-origin`). Only nonce-authorized preview code and bundled MathJax can execute. External HTTPS CSS/fonts can load; images, scripts supplied by the user, forms, navigation, and API requests are blocked.
- CSS is assigned as text to a stylesheet, never interpolated into HTML. App theme changes do not alter the white image canvas.
- External stylesheets load directly in the browser for preview; the renderer fetches and sanitizes them separately. Network availability, CSS sanitization, and browser behavior can make the preview differ from final output. The generated server image is authoritative.
- `/img` is fetched anonymously on user action and displayed as an image blob. `/url` is called on user action; the returned `/image` permalink opens in a tab reserved during the click, with an explicit link fallback for popup blockers.
- Inputs are not saved automatically. Only the theme preference is persisted.
