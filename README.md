# PermaFormula

Create SVG and JPEG images from [AsciiMath](https://asciimath.org/) formulas or styled
HTML, then share them through self-contained permanent URLs.

**Try the live system: [https://permaformula.kozow.com](https://permaformula.kozow.com).**

The Builder provides live previews, HTML/CSS editing, external stylesheet support,
SVG/JPG links, light and dark themes, and a public image library. Library entries
can restore the original source, styles and canvas width for editing.

## API

| Endpoint | Purpose |
| --- | --- |
| `GET /exp-svg?formula=...` | Render a plain AsciiMath expression as SVG. |
| `GET /exp-jpg?formula=...` | Render the same expression as JPEG. |
| `GET /ubcq?formula=...` | Encode HTML, CSS and layout as a compressed descriptor. |
| `GET /html-svg?ubcq=...` | Render a descriptor as styled SVG. |
| `GET /html-jpg?ubcq=...` | Render a descriptor as JPEG. |
| `GET /ubcq-decode?ubcq=...` | Decode a descriptor without rendering or fetching styles. |
| `GET /library-items` | List published sources with cursor pagination. |
| `GET /library-item?id=...` | Retrieve one published source. |

For a local API on port 8086:

```bash
curl --get --data-urlencode 'formula=sqrt(x^2 + y^2)' \
    http://localhost:8086/exp-svg --output formula.svg
```

For styled HTML, obtain a descriptor and pass it to an image endpoint:

```bash
ubcq=$(curl --get --data-urlencode 'formula=<p>`x^2`</p>' \
    --data-urlencode 'style=p { color: navy; }' \
    --data-urlencode 'imageWidth=500' http://localhost:8086/ubcq)
curl --get --data-urlencode "ubcq=$ubcq" \
    http://localhost:8086/html-svg --output styled.svg
```

`ubcq` is GZIP-compressed, Base64-encoded JSON. URL-encode it once in requests;
`curl --data-urlencode` does this for you. Formula endpoints accept plain `formula`,
not UBCQ. The historical descriptor field name is `forumla`.

Both image formats reuse validated SVG rendering and bounded caches. JPEG uses a white background;
styled SVG uses `foreignObject` and is intended for browsers. Failed renders are not
cached. See the [API reference](docs/api.md) for parameters, responses and examples.

## Run locally

Use Bash 4+ on Linux or Git Bash on Windows. Java development requires JDK 25,
Maven and Node.js 24/npm. Set `JAVA_HOME` to the JDK installation directory.

```bash
bash scripts/local/install-rendering.sh
bash scripts/local/start-local.sh --admin-password 'your-admin-password' --no-tunnels
```

Open [localhost:5173](http://127.0.0.1:5173). The API listens on port 8086 and the
renderer on 9090. From another terminal:

```bash
bash scripts/local/stop-local.sh
```

Omit `--no-tunnels` to start Cloudflare API/UI tunnels; this requires `cloudflared`.
Use `--skip-build` to reuse artifacts or `--ui-port 5174` to change the UI port.
The scripts activate nvm's default Node version when Node is missing from `PATH`.

For Eclipse-owned Java services, start only Vite:

```bash
bash scripts/local/start-ui.sh --api-origin http://localhost:8086
```

See [local development](scripts/local/README.md), [Eclipse setup](eclipse/README.md)
and the [frontend guide](frontend/README.md).

## Run with Docker

With Docker's Linux engine and Compose available:

```bash
cp .env.example .env
# Set distinct strong database passwords in .env before starting.
docker compose up --build -d
```

Open [localhost:5000](http://localhost:5000). The default profile serves the Builder,
API and images through one origin. Set `PERMAFORMULA_HOST` for your deployment and
terminate HTTPS in front of the loopback listener. A separate-origin profile is
available in `compose.separate-origins.yaml`.

Compose runs the API, isolated renderer, PostgreSQL, nginx ingress and stylesheet
egress proxy. See [container configuration and verification](docker/README.md).

## Library, privacy and administration

Successful SVG rendering queues its source for public library publication. JPEGs
share that entry; descriptor creation alone does not publish it. **Rendered source
is public: do not submit confidential content.** Publication is best effort and
may lag rendering. Expired or evicted entries disappear; permanent URLs can
regenerate images while supported by the service.

External preview stylesheets and fonts load only after **Load styles**. External
hosts can then observe your browser's IP address and request timing. Server rendering
fetches and validates styles separately. Previews run in sandboxed iframes; server
rendering applies input/output validation, deadlines and resource limits.

Open `/admin.html` and sign in as `admin` using `PERMAFORMULA_ADMIN_PASSWORD`.
Without a configured password, login is disabled. Admin adds best-effort cache
eviction; it does not permanently ban content. Image requests remain public.

## Development

| Module | Responsibility |
| --- | --- |
| `permaformula-api` | HTTP API, validation, stylesheet fetching and caching |
| `render-contract` | Shared policy, Protocol Buffers and gRPC types |
| `renderer-core` | MathJax/GraalJS and Chromium rendering with worker supervision |
| `renderer-service` | Private gRPC endpoint and renderer lifecycle |

Build the frontend before packaging the Java application:

```bash
npm --prefix frontend ci
npm --prefix frontend run build
mvn verify
```

Maven uses checked-in renderer bundles. Rebuild them after changing `tools/svg`;
Node.js is needed for development/builds, not the deployed runtime.
Browser and PostgreSQL tests require explicit opt-in and their prerequisites.

Local persistence defaults to SQLite; Docker uses PostgreSQL. Both support bounded
caching and Liquibase migrations. Add schema changes as new changesets and preserve
applied history; startup does not automatically reset existing databases.

## Documentation

| Topic | Guide |
| --- | --- |
| Scripts and offline checks | [Scripts](scripts/README.md) |
| API parameters and errors | [HTTP API](docs/api.md) |
| Library behavior and pagination | [Library](docs/library.md), [admission](docs/library-admission.md) |
| Cache and runtime settings | [Runtime reference](docs/runtime-reference.md), [storage](docs/cache-storage.md), [availability](docs/cache-availability.md) |
| Validation and resource bounds | [Validation inventory](docs/validation-inventory.md), [input limits](docs/input-limits.md) |
| Browser and database tests | [Optional tests](docs/optional-tests.md), [Docker checks](scripts/tests/docker/README.md) |
| Renderer bundles | [SVG tooling](tools/svg/README.md) |
| Branding assets | [Branding](docs/branding.md) |

## License

Code and documentation are [MIT-licensed](LICENSE). The PermaFormula name, logo and
project artwork are reserved separately; see [NOTICE.md](NOTICE.md). Third-party
materials retain their own licenses.
