# Overall security review — 2026-10-08

## Scope and conclusion

Fresh review of commit `576feb3aa3a631c6627a90fbc9c7422c3c90294c`, including the Java API, descriptor/compression pipeline, CSS fetching and sanitization, SVG validation, gRPC transport, renderer supervision/queue/pools, React builder and preview, SQLite/PostgreSQL caching, build dependencies, and supplied Docker/nginx/Squid configuration. The working tree was clean at review start. `sec.md` was read for context, but its historical findings and test results are not treated as current proof. This report leaves that document unchanged.

The application has substantial defense in depth against submitted scripts, stylesheet injection, server-side SSRF, XML external entities and active SVG. No confirmed remote code execution, server-side SSRF bypass, SQL injection or delivered-SVG script execution was identified in this review. This is not assurance that such vulnerabilities are absent.

The most actionable gaps are the weaker live-preview policy, availability controls around persistent-cache work, and the database's soft storage budget. Reusable workers also change the isolation model. Deployment-dependent decisions are separated below because the eventual hosting, domains, network and storage are undecided.

This is a source assessment with existing regression checks, not a penetration test or complete CVE audit. Findings explicitly distinguish code-established behavior from conditional exploit impact. Severity assumes an anonymous public rendering service; local-only usage has lower exposure. No application fixes or deployment changes are included.

## Application findings

### A1. Live preview permits uncontrolled stylesheet/font requests — Medium

**Evidence:** `frontend/src/preview-document.ts` inserts validated stylesheet references directly as `<link>` elements, copies raw CSS through `textContent`, and sets `style-src 'unsafe-inline' https:` and `font-src https: data:`. `frontend/src/api.ts:validStyleRef` checks HTTPS and credentials only; it does not apply the API's port/address checks. `Preview.tsx` uses `sandbox="allow-scripts"`, without same-origin privileges, and disables referrers.

The sandbox and DOMPurify provide useful script/parent isolation, but they do not reproduce the server's controlled CSS fetch/sanitization boundary. An external sheet, CSS `@import`, or `@font-face` can cause browser requests to arbitrary HTTPS destinations. For example, CSS containing `@import url("https://example.invalid/probe.css");` is inserted unchanged. A stylesheet can conditionally select permitted HTML attributes and load fonts using `unicode-range`, creating potential content-dependent network signals. These mechanisms were identified from source; a successful content-exfiltration demonstration was not performed.

Impact is on the person previewing the input: third-party tracking, possible disclosure of preview content, and requests toward destinations reachable from that browser. Private-network requests remain subject to browser restrictions, TLS and network policy; this is not a demonstrated private-network bypass or server SSRF. Current UI inputs are manually entered, with no discovered automatic loading of attacker-controlled shared documents, which limits the attack scenario.

**Recommended change:** sanitize preview CSS/inline declarations with a compatible presentation policy and supply already-sanitized external CSS through the controlled server fetch path. Restrict preview CSP to the resources actually required, especially fonts and stylesheet origins. If raw remote previewing is deliberately retained, make remote loading explicit and explain that styles are fetched from the viewer's browser. Add browser regressions that capture all network requests, including imports, redirects and font loads.

### A2. Live preview lacks the server's complexity and termination controls — Medium

**Evidence:** `Preview.tsx` regenerates the frame after a 200 ms debounce and changes a status label after 15 seconds. `preview-document.ts` sanitizes markup on the main UI thread, then typesets MathJax and applies unrestricted CSS in the frame. It does not apply `ComplexityPolicy`, server HTML/CSS/math budgets, or a hard termination mechanism.

Very large pasted HTML/formulas, costly layouts or font/data resources can consume browser CPU/memory before the API ever rejects the input. The timeout changes UI status; it does not stop typesetting or remove the document. The iframe's origin isolation does not guarantee CPU/memory isolation from the tab. No browser-hang payload was executed during this review.

**Recommended change:** impose input/node/math/CSS budgets before preview construction, reject oversize paste without first creating another full document, and use server rendering or an explicitly started preview for expensive input. On timeout, discard the frame where feasible. Add controlled resource-stress checks rather than destructive browser-hang tests.

### A3. Persistent-cache work bypasses API generation admission and its cancellable deadline — Medium

**Evidence:** `CaffeineImageStore.getOrGenerate` invokes `persistent.find` before the supplied generator enters `work.generate`; it then invokes `persistent.accessed` synchronously for every successful response, including memory hits. `JdbcImageCache.find` retrieves a full string and calls `SvgOutputPolicy.sanitize` on the caller thread. `ApiDeadlineFilter` establishes a deadline context but does not independently interrupt arbitrary servlet work. JDBC operations do not consult `WorkDeadline`.

Generation concurrency of two therefore does not bound simultaneous database reads/output parsing. The request limit still bounds concurrency per client, and servlet/pool capacities provide other limits, so this is not unlimited thread creation. However, distributed clients can drive validation of large persistent hits and synchronous metadata writes without consuming render tokens. Slow storage can also delay otherwise available memory hits. Hikari connection acquisition has a one-second timeout; PostgreSQL has socket/statement/lock timeouts, while SQLite has a busy timeout. These reduce common stalls but do not establish an end-to-end hard deadline for parsing and all database paths.

The stored `size_bytes` predicate is metadata, not a check of actual SVG length before JDBC materialization. A compromised writable cache can return an oversized string before validation rejects it. That consequence requires cache write access; no unauthenticated database-write path was found.

**Recommended change:** give persistent reads and output validation explicit bounded admission and remaining-deadline handling. Avoid a synchronous database write for every memory hit by coalescing metadata touches with bounded pending state. Bound actual stored payload size at the database/read boundary as well as in `SvgOutputPolicy`. Verify memory-hit latency during database failure and concurrent cold persistent hits with near-limit SVGs.

### A4. Persistent size limit is periodic logical eviction, not a hard disk quota — Medium

**Evidence:** `JdbcImageCache.save` checks each artifact against `maximumBytes`; aggregate eviction runs only in scheduled `cleanUp`, five minutes apart by default. Cleanup sums `size_bytes`, excluding row/index/database/WAL overhead. The schema checks only that reported size is positive. There is no reservation or transactional aggregate quota before insert.

Distinct valid render requests can increase storage beyond the configured 2 GB between cleanups. Actual disk consumption can exceed the logical budget, and deleting rows does not itself promise that database files, WAL or backups immediately shrink. Per-client render limits and global renderer capacity reduce growth speed, but do not make the configured number a hard storage boundary. Disk exhaustion was not reproduced.

**Recommended change:** enforce an insertion-time budget or high-water admission threshold, define row-count/overhead accounting and reclaim behavior, and stress-test growth and cleanup under concurrent writes. Keep physical filesystem/volume quotas in the deployment section. Document the current setting as a logical retained-content target.

### A5. Successful jobs share browser/native and GraalJS state — Medium, conditional isolation risk

**Evidence:** `SupervisedRenderer` maintains separate formula and HTML worker pools. `RenderWorkerMain` handles multiple requests; the default successful-job lifetime is 100. HTML jobs create fresh `BrowserContext`s, while the Chromium process survives. Formula jobs reuse one `AsciiMathRenderer` context and one module-level MathJax document in `tools/svg/render-formula.js`. Failed/timed-out workers are retired. The environment is cleared and guest Java host access/class lookup are disabled.

This differs from `sec.md`'s fresh-JVM-per-job claim. Fresh HTML contexts prevent ordinary page globals/cookies from carrying over, but a successful native compromise can persist into subsequent jobs of that worker. A guest-library state bug could affect later formula jobs. Existing `WarmRenderingTests` cover ordinary HTML global leakage and representative formula reuse; they do not prove isolation after native compromise or arbitrary stateful MathJax behavior. No ordinary cross-request data leak was established.

**Recommended change:** document reuse as an explicit performance/security tradeoff. Where requests require a stronger tenant boundary, set `RENDERER_WORKER_MAX_JOBS=1` and account for process startup cost. Expand formula-state regressions and worker-lifetime tests; process reuse alone is not evidence of an exploitable leak. Host/container isolation remains a separate deployment requirement.

### A6. API parsers remain interruptible only on a cooperative basis — Medium, residual

**Evidence:** `BoundedWork` cancels a `FutureTask` and interrupts its thread at expiry. Admission is deliberately held until actual exit. HTML, CSS and SVG parsing execute inside the API JVM; source/structural budgets exist, but thread interruption cannot forcibly terminate a non-cooperative library call.

A pathological accepted input or parser defect could occupy scarce preparation/generation slots or CPU after an HTTP timeout. The implementation prevents repeated timeouts from spawning unlimited admitted workers, which materially limits this risk. No current non-terminating parser payload was found.

**Recommended change:** retain adversarial parser regressions and slot-retention checks. If hard termination is required, move untrusted preparation/output validation into a supervised process with bounded messages; increasing thread concurrency does not solve non-cooperation.

### A7. Permalinks and caches provide no content confidentiality or deletion contract — Low to Medium, depending on data

**Evidence:** `/exp` includes plaintext formulas in URLs; `/html` carries a reversible GZIP/Base64 descriptor. `/url` itself receives content/CSS in a GET query. Persistent caches store rendered SVG as text, indexed by a deterministic SHA-256 identity. Access extends the 60-day idle TTL, including persistent hits. There is no user ownership, private-link authorization, expiry encoded into the permalink or public deletion interface.

Anyone with a link can recover its content; the hash is an identity, not access control. Low-entropy formulas also have guessable content identities for someone who can inspect the cache. Endpoint logging omits query arguments, but browser history and other request-recording systems can retain them. Rendered content now persists across restarts, contrary to the old report. No unintended public cache enumeration endpoint was found.

**Recommended change:** state the public/reversible nature of links and persistence in user-facing documentation. Decide whether sensitive content is supported. If it is, add an authenticated/private object model and explicit expiry/deletion semantics. Use a fixed maximum age alongside idle TTL when finite retention is required. Host logs, database access and backups are deployment considerations below.

### A8. External stylesheet changes do not invalidate content cache identities — Low, integrity/freshness

**Evidence:** `GeneratedImageService` hashes the descriptor, versions and complexity fingerprint, including stylesheet URLs but not downloaded stylesheet bytes. Both caches use expiry after access.

A changed external sheet may leave an old rendering indefinitely hot; a malicious or unintended initial stylesheet version can persist until eviction/version changes. Sanitization and final SVG validation still apply, so this is not an active-content bypass. The current behavior may be acceptable for stable permalinks, but is not a guaranteed snapshot or guaranteed refresh policy.

**Recommended change:** define whether links capture a stylesheet snapshot or periodically refresh. Use content hashes, bounded absolute age or explicit refresh/version inputs accordingly.

## Controls reviewed and retained

| Boundary | Current control | Remaining limit |
| --- | --- | --- |
| Descriptor/decompression | Bounded Base64/compressed/expanded bytes; constrained JSON depth/tokens/strings; constructor field validation; trailing-token rejection | Large accepted requests still allocate multiple representations; HTTP parsing precedes application field checks |
| Submitted HTML/CSS | OWASP HTML allowlist, parsed CSS policy, escaped style boundary, nonce CSP, pre/post DOM and CSS complexity checks | Custom presentation policy needs regression maintenance; accepted layout cost is not fully predictable |
| Server CSS fetching | HTTPS/443, destination checks, direct-mode validated DNS used for connections, redirect revalidation, MIME/body/import/deadline limits; configured proxy has no direct fallback | Public HTTPS hosts are intentionally allowed; proxy mode depends on the proxy enforcing destination policy |
| Browser renderer | Fresh contexts, blocked service workers/downloads/frames/popups/navigation, context request/WebSocket interception, trusted local assets only | Application guards do not replace native/network isolation; browser process is reused |
| Formula renderer | Value-based JS invocation, no Java host access/class lookup, math/tree/output budgets, supervised JVM | GraalJS/MathJax state survives successful jobs |
| SVG output | Independent API StAX allowlist, denied DTD/entities/resolution/PI, CSS filtering, local resource references, constrained HTTPS hyperlinks, dimensions/node/byte limits | Permitted hyperlinks can point to untrusted sites; downloaded/blob SVG may lose HTTP headers |
| Delivery | SVG sandbox CSP, nosniff and no-referrer; configured ingress marker checked against trusted peer | Origin/credential isolation depends on selected deployment profile |
| Availability | Bounded API work, per-IP tokens/concurrency/state cap, shared cache loads, bounded renderer FIFO/retained bytes, RPC deadlines and worker retirement | Per-instance/IP fairness; cooperative API cancellation; persistent-cache gap in A3 |
| Database | Parameterized key/content queries, quoted initialization password, policy-versioned keys and revalidation of persistent SVG, fail-open cache failures with rate-limited diagnostics | Storage tampering can alter allowed presentation; logical size accounting and synchronous hit writes |
| Logs/build | Endpoint query omission, generic sanitizer/parser errors, hashed cache keys, lockfiles and local generated assets | No complete dependency/advisory audit performed; broader diagnostic infrastructure unverified |

Current output version is `svg-output-policy-3`, HTML format `html-svg-v9`, formula format `formula-svg-v4`. New format identities prevent ordinary reuse of older policy keys. SHA-256 collision exploitation was not identified. No source-based SQL injection, executable formula interpolation, or submitted style-tag breakout was found.

## Deployment-dependent issues — decisions pending

These are requirements and conditional risks for the eventual environment, not findings that an undecided production deployment is already insecure. Supplied Compose files are reference implementations. The separate-origin profile was subsequently built and run as a disposable local test deployment; production infrastructure remains undecided and unverified.

### D1. Choose the delivered-image trust boundary

Default `compose.yaml` now uses one host; `compose.separate-origins.yaml` supplies separate origins. The default no longer matches `sec.md`'s two-origin description. Both rely on byte-level SVG filtering and anonymous image ingress. If the builder/API will share an origin or cookie domain with accounts or sensitive applications, choose independently isolated image delivery and verify credential stripping, backend privacy, marker overwrite, normalized routes and cookie scope. Distinct ports isolate origins but not cookie domains. Separate registrable domains provide stronger cookie separation. For a dedicated anonymous service, document the accepted single-host residual risk.

### D2. Enforce OS/network isolation outside application code

Verify renderer sandbox availability, nonroot identities, immutable assets, dropped capabilities, seccomp, resource/PID/tmpfs limits, no unrelated mounts/credentials, and restart/cleanup behavior on the chosen host. Compose sets renderer networking to none and provides a Unix socket; another runtime must supply equivalent boundaries. Maintain independent stylesheet egress destination enforcement if proxy mode is used. Account for API access to the database network as well as its ingress/egress network. Native compromise is not contained by URL validation alone.

### D3. Protect renderer transport and internal services

Unix socket permissions/private mounts are workload-level authorization. Loopback plaintext TCP is suitable only within the intended local trust boundary; remote/shared-network gRPC needs verified TLS and client authentication/access policy. The renderer accepts prepared HTML rather than performing the API sanitizer itself, so unauthorized direct callers must not reach it. Keep database, Squid, backend API and health endpoints private. Validate connection/message-flood behavior at the selected transport boundary.

### D4. Budget ingress memory before header parsing

Both nginx templates retain `large_client_header_buffers 2 12m` with a 256 MiB ingress container and 256 worker connection slots. `limit_conn` counts requests only after complete headers, so partial large headers can consume resources before quotas apply. A comparatively small number of large concurrent requests can threaten the memory budget; no OOM probe was run. Enforce connection/header-memory admission at the actual front door, or redesign large-input submission around bounded POST bodies/short object identifiers. Verify header/body/write timeouts, TLS handshake limits, maximum request lines and overload recovery. [nginx connection-limit semantics](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html).

### D5. Set fleet-wide abuse controls and trusted client identity

Application quotas are per IP and per instance; replicas/restarts alter effective allowance and NAT users share limits. Select trusted-peer/forwarded-header rules for the real load balancer, prevent direct backend access and test spoofed/duplicate headers. Introduce shared quotas or account-based fairness if the deployment needs them. Set CPU/memory/admission limits from measured worst-case requests; renderer pool startup creates both HTML and formula workers. Public HTTPS fetching also needs an abuse/egress policy appropriate to the service.

### D6. Protect persistent data, secrets and physical storage

Choose SQLite, PostgreSQL or memory explicitly. Apply filesystem/database access restrictions, physical quotas, retention/purge/backup policy and encryption appropriate to the supported data. Do not reuse smoke credentials. The runtime application role currently has schema CREATE rights for Liquibase; consider a separate migration role if least privilege is required. For database transport across an untrusted network, require certificate/hostname verification rather than relying on a private Compose network or implicit driver defaults. SQLite files/WAL and PostgreSQL volumes contain rendered content. Physical quota and reclaim monitoring are required in addition to A4's logical eviction.

### D7. Establish TLS, logs and browser response policy

Provision HTTPS/certificates and the appropriate HSTS policy at the selected public front door. Confirm query redaction across proxy error logs, access logs, tracing/APM, analytics and support tooling; disabling access logs alone does not establish redaction of error diagnostics. Add a builder response CSP and restrictive permissions/framing policy compatible with the preview. The opaque iframe sandbox is a useful isolation control, but remote resource loading is separately governed by its CSP. [iframe sandbox and referrer behavior](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/iframe).

### D8. Establish patch and artifact verification

Inventory resolved Maven/npm dependencies, Chromium, GraalJS, MathJax, html-to-image, JDK, nginx, Squid, PostgreSQL and base OS packages. Maintain advisory scanning, browser updates, lockfile/immutable-image builds and generated-bundle checks. Declared versions and passing tests do not establish freedom from CVEs. This review does not assert current CVE status or recommend a specific hosting provider.

## Verification performed for this review

- Full offline `mvn -o verify`: BUILD SUCCESS across all modules. Current-run Maven summaries total 259 tests: 221 passed, 38 skipped, no failures/errors. Browser rendering, PostgreSQL integration, Swagger and deployed-image tests are opt-in; a skipped test is not counted as security verification. Build log: `target/sec2-maven.log`.
- Frontend unit tests: seven passed (`npm test`).
- Existing frontend Chromium suite: six passed (`npm run test:browser`, local Chromium 1217). Its remote stylesheet is intercepted by a test fixture; passing it does not establish absence of real remote CSS/font traffic.
- Source inspection covered current policy and resource boundaries, including persistent cache SQL/schema, worker reuse, frontend preview, anonymous delivery and both ingress profiles.
- No production deployment, fresh container isolation/egress smoke run, database stress test, network exploit/exfiltration probe or complete dependency scan was performed. Historical results in `sec.md` are not carried forward as tests run here. Follow-up local delivery verification is documented below.

### Follow-up: previously skipped tests — 2026-10-08

All 38 tests skipped in the initial Maven run were enabled and passed across the following runs. This is combined coverage, not a claim that a single all-enabled full reactor run passed.

| Suite | Previously skipped tests passed | Evidence and scope |
| --- | --- | --- |
| `BrowserChannelPolicyTests` | 6 | WebSockets/service workers, workers/WebRTC, popups, non-network frames, navigation/reload and asset redirects; `target/sec2-optin-elevated.log` |
| `WarmRenderingTests` | 3 | Production supervisor renders both kinds, warm formula reuse and ordinary HTML global-state isolation; same log |
| `BrowserRenderingTests` | 9 | Server math/layout complexity rejection and recovery, script-load errors, MathJax link safety, hostile input/loopback denial, styles and cross-request isolation; `target/sec2-optin-retry.log` |
| `BuilderUiTests` | 2 | Packaged assets and real formula/styled-HTML rendering through the builder; same retry log |
| `SwaggerUiTests` | 1 | Real permalink and image endpoint execution through Swagger; same retry log |
| `PostgresImageCacheTests` | 16 | Disposable PostgreSQL: restart reads, idle TTL, corruption rejection, failure handling, concurrent generation sharing, logical cleanup, UTF-8 budgets and migrations; `target/sec2-postgres.log` |
| `ImageDeliveryBrowserTests` | 1 | Real separate-origin Compose deployment: anonymous redirects/CORS and rejection of credentialed cross-origin reads; `target/sec2-delivery.log` |

The initial sandboxed browser run could not launch Chromium and failed for that environmental reason. A permitted run outside the sandbox passed all nine renderer opt-in tests, but stopped on the already-enabled `SupervisedRendererTests.queueCancellationKillsRunningChildAndRetainsAdmissionUntilExit`: its child PID fixture did not appear within five seconds. An isolated retry of all four `SupervisedRendererTests` passed, alongside the 12 API opt-in tests. The first timeout remains recorded as a transient fixture/startup failure; no timeout was increased and no application/test code was changed to force success.

Fresh API/renderer/ingress images were built for a disposable `permmath-sec2` separate-origin Compose project. `scripts/test-image-delivery.ps1` also passed image-only routes, raw-query redirects, SVG response headers/CORS, forged private-API markers including matrix paths, native request credential stripping, and response cookie suppression. Evidence: `target/sec2-compose.log` and `target/sec2-delivery-smoke.log`. Local localhost ports verify origin behavior, not production cookie-domain/TLS isolation. Disposable containers, networks and database volumes were removed after testing.

These results strengthen evidence for existing server rendering, ordinary worker state isolation, persistent-cache output safety, and local delivery controls. They do **not** close A1–A2: server/browser-builder functionality tests do not exercise preview CSS exfiltration or browser resource exhaustion. A3–A4 remain open because cache contract tests do not measure worst-case concurrent cold-hit parsing, outage latency, aggregate insertion-time enforcement or physical disk exhaustion. A5 remains conditional: ordinary state-isolation tests do not simulate a successful native compromise or prove arbitrary MathJax-state isolation. A6 hard parser termination, A7 confidentiality/retention, A8 stylesheet freshness and production D1–D8 decisions remain as stated.

## Priorities

1. Align live-preview CSS/network and complexity limits with the service's security expectations (A1–A2).
2. Bound persistent reads/validation and metadata writes; establish actual storage budget semantics (A3–A4).
3. Decide worker reuse, supported data confidentiality/retention, and stylesheet freshness behavior (A5, A7–A8); retain non-cooperative parser protections (A6).
4. Once hosting is selected, resolve and verify D1–D8 against that concrete environment.
