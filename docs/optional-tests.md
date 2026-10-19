# Optional Java tests

Ordinary Maven runs intentionally skip tests that need Chromium, a disposable database,
or a running deployment. These are maintained integration checks, not disabled regressions.
Build the frontend with `npm --prefix frontend ci` and `npm --prefix frontend run build`
before testing packaged Builder assets. Install the Playwright Chromium version matching
the Java dependency into `.runtime/browsers` before browser tests.
Frontend browser tests use a separate `.runtime/frontend-browsers` cache; install
their matching revision with `npm --prefix frontend run test:browser:install`.

| Switch | Tests | Prerequisite |
| --- | --- | --- |
| `rendering.tests=true` | BrowserRenderingTests, BrowserChannelPolicyTests, WarmRenderingTests | Local Chromium |
| `ui.tests=true` | BuilderUiTests | Local Chromium and built frontend |
| `swagger.tests=true` | SwaggerUiTests | Local Chromium |
| `postgres.tests=true` | PostgresImageCacheTests | Disposable PostgreSQL |
| `delivery.tests=true` | ImageDeliveryBrowserTests | Local Chromium and separate-origins deployment |
| `ubcq.measure=true` | UbcqLibraryMeasurementTests | Explicit local measurement fixture |

Run the self-contained browser groups from the repository root:

```sh
mvn test "-Drendering.tests=true" "-Dui.tests=true" "-Dswagger.tests=true"
```

PostgreSQL tests reset cache/schema state. Use only a disposable database, set
`PERMAFORMULA_POSTGRES_TEST_PASSWORD`, and supply its URL using `-Dpostgres.test-url=...`.
See [Docker testing instructions](../docker/README.md) for the command and deployment
profiles. SQLite contract tests already run in ordinary Maven verification.

The delivery browser test covers the supported separate-origins profile, including
anonymous redirects, SVG/JPEG delivery and denied credentialed cross-origin reads.
It requires a separately started disposable deployment; it does not start one itself.
Defaults are API `http://localhost:5017` and images `http://localhost:5018`, overridable
with `delivery.api-url` and `delivery.image-url`. Single-host delivery has separate
ordinary Java coverage in SingleHostImageDeliveryTests.

The library measurement is an experiment rather than a routine regression suite.
Run `bash scripts/tests/measure-ubcq-library.sh` to create and remove its own disposable
PostgreSQL container. It measures source/token sizes, database transfer, allocations,
and held-response memory. Its additional disposable-database guard is intentional.

BuilderPageControllerTests also skips its packaged crawler-page check if
`static/index.html` is absent. Build the frontend first to include that check.
