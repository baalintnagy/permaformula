# Docker regression checks

Run from the repository root in Bash (Linux or Git Bash on Windows). Node.js 24 and
Docker's Linux engine/Compose are required; PowerShell and frontend npm packages are
not required. Bash entrypoints invoke Node helpers for HTTP and raw TCP checks.

```bash
# Disposable ingress fixtures; build once:
docker build -f docker/egress/Dockerfile --target ingress -t permaformula-ingress-security .
bash scripts/tests/docker/test-production-ingress.sh --image permaformula-ingress-security --profile single-host
bash scripts/tests/docker/test-production-ingress.sh --image permaformula-ingress-security --profile separate-origins --trust-host-proxy
bash scripts/tests/docker/test-ingress-limits.sh --image permaformula-ingress-security
```

The header fixture checks all SVG/JPEG/library routes, credential stripping, forged
markers, response cookies, method/path restrictions and trusted/untrusted forwarding.
The limits fixture checks 11 MiB request lines, body limits, shared client/total caps,
Retry-After/CORS, header/body/keepalive/write timeouts and capacity recovery.
Disposable container/network names are unique; cleanup removes only owned resources.

These checks require a separately started smoke deployment; see
[container deployment](../../../docker/README.md#verification) for Compose commands:

```bash
bash scripts/tests/docker/test-egress.sh --project-name permaformula-egress-smoke --base-url http://localhost:5017
bash scripts/tests/docker/test-image-delivery.sh --project-name permaformula-delivery-smoke --base-url http://localhost:5017 --image-url http://localhost:5018
bash scripts/tests/docker/test-rate-limits.sh --base-url http://localhost:5017
bash scripts/tests/docker/test-render-queue.sh --project-name permaformula-queue-smoke
```

Rate-limit checks require a fresh deployment with `docker/rate-limit/smoke.override.yaml`;
other smoke checks use normal quotas. The caller stops/removes the smoke Compose
project. Egress uses public HTTPS CDN resources and a test-only DNS fixture; rate and
queue tests send synthetic renders. Use `--help` for each command's options.

The large-input fixture requires a single-host smoke project with explicit transport
settings, including bounded Tomcat header-buffer concurrency:

```bash
docker compose -f compose.yaml -f docker/input-limits/smoke.override.yaml --env-file docker/egress/smoke.env -p permaformula-input-smoke up --build -d
bash scripts/tests/docker/test-input-limits.sh
docker compose -f compose.yaml -f docker/input-limits/smoke.override.yaml --env-file docker/egress/smoke.env -p permaformula-input-smoke down --volumes
```

Application byte limits do not override the HTTP server's independent request-line
limit. Use the fixture's override rather than increasing header buffers without
bounding connections and processors.

Dependency-free HTTP/argument helper tests (no Docker):

```bash
node --test scripts/tests/docker/test-utils.test.cjs
```
