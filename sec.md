# Security reassessment — 2026-10-06

Reviewed the current Maven modules, rendering policies, gRPC service and Docker configuration, including the SVG output hardening following commit `bebf5b6`. This replaces the original assessment; finding numbers are retained for continuity. Removed JavaFX functionality is excluded.

This is a source review with focused local rendering probes, not a penetration test, a full dependency vulnerability scan or verification of production infrastructure. Deployment controls outside this repository remain unverified. Previous browser-enabled Maven verification: 111 tests, 110 passed and one opt-in Swagger UI test skipped, including output-policy and response-header regressions. Existing coverage includes gRPC transport/cancellation, correlation and supervised process termination/recovery. Styled SVG is also checked visually and in Chromium both as an image and as a document with the delivery CSP.

The submitted HTML/CSS execution paths have been substantially hardened, and external styles remain supported through controlled fetching. Generated SVG passes an independent API-side presentation policy before caching or delivery. Decompression/endpoint payload limits (11), parser/render budgets (12), per-client request/cache-miss controls (13–14), separate image delivery in Compose (8) and Compose's independent stylesheet egress boundary (3) are implemented. Next priorities are **production verification and operational patch/privacy controls**; non-cooperative API work still needs consideration (13).

## Findings

### 1. JavaScript execution through submitted HTML — addressed for submitted markup

`RenderContentPolicy` applies OWASP Java HTML Sanitizer before Chromium parses the document. The allowlist permits presentation HTML and limited attributes, excludes scripts, event handlers, submitted SVG, frames, forms and links, and sanitizes inline CSS. The trusted template receives nonce-based CSP; only trusted MathJax/exporter scripts are intended to execute.

The original raw-markup execution path is no longer present. MathJax-generated DOM and final SVG have separate controls under 8. Keep hostile-markup regression coverage when expanding the supported HTML subset.

### 2. Breaking out of the stylesheet — addressed for submitted CSS

`StylesheetPolicy` parses CSS, restricts properties and value functions, rejects resource URLs and unsupported rules, and escapes literal `<` before stylesheet embedding. `RenderContentPolicy` builds the document with DOM APIs rather than substituting raw input into the template. Inline declarations use the same policy. External CSS is also sanitized.

The original closing-style-tag injection path is addressed. CSS parsing/layout budgets are implemented under 12; worker execution limits remain necessary for costly accepted layouts.

AntiSamy 1.7.8 was evaluated as a replacement for custom CSS filtering on 2026-10-06.
Default and permissive diagnostic policies lost required variables, calculations,
responsive conditions and modern rules; Chromium confirmed corrupted grid and gradient
values. It was not added to production dependencies. The reproducible standalone
[evaluation](tools/antisamy-trial/README.md) records the compatibility limits.

### 3. SSRF against internal services — application and Compose egress boundaries implemented

Chromium has a deny-by-default request policy: the approved document and a narrow set of trusted local scripts are permitted. CSS is fetched outside Chromium by `StylesheetFetcher`, preserving `styleRefs` and supported imports.

The fetcher requires HTTPS on port 443, rejects credentials/fragments and inappropriate destinations, validates all DNS answers and supplies those validated addresses to the actual HTTP connection. Redirects are manually revalidated. TLS verification remains enabled; automatic redirects, retries, cookies and response compression are disabled. CSS MIME type and streamed body size are checked.

Current bounds: eight top-level references, 16 resources including imports, import depth three, three redirects per resource, 1 MiB per response and 4 MiB combined decoded CSS measured after UTF-8 re-encoding. Requests share a 15-second network deadline with five-second connection/socket/response timeouts.

Compose now forces downloads through Squid using Apache HttpClient's native
`DefaultProxyRoutePlanner`. Squid resolves destination hosts and checks all answers
with its native destination ACL before opening a public port-443 CONNECT tunnel.
Any private, loopback, link-local/metadata, reserved or IPv6 transition answer
rejects the request, including mixed DNS results. Java retains URL/redirect checks,
TLS verification and streamed CSS budgets; proxied mode resolves only the trusted
proxy in the API. There is no direct fallback on proxy failure. Standalone direct
development mode retains Java's validated DNS snapshot resolver.

The API is attached only to an isolated internal Docker bridge without a default
route, host bridge address or published port. nginx serves HTTP ingress; Squid is
the sole outbound HTTP proxy, on a separate external network. Both run nonroot,
without extra capabilities, with read-only filesystems and resource bounds. The
renderer retains `network_mode: none`. The proxy is required in Compose; missing
configuration fails startup. Docker's isolated gateway mode is required because a
plain internal network still allows access to host services on its bridge address.
[Docker gateway modes](https://docs.docker.com/engine/network/port-publishing/#gateway-modes),
[Squid destination ACLs](https://www.squid-cache.org/Doc/config/acl/).

Arbitrary public HTTPS hosts remain allowed, so this is still a bounded public-site
fetcher. Per-client render quotas are implemented under 13; an optional domain
allowlist remains an improvement for deployments that do not need arbitrary hosts.
Standalone/Eclipse direct mode and deployments outside Compose require equivalent
independent controls. Native proxy/kernel vulnerabilities and production host/network
policies remain operational concerns. Java CSS processing may still ignore
interruption; see 13. DNS-race resistance is not established by static DNS tests.

### 4. Alternative browser network channels — application guards and isolated renderer network boundary implemented

`BrowserChannelPolicy` uses a fresh context with service workers blocked, downloads disabled and context-wide HTTP/WebSocket handling. WebSockets never connect to their destination. Additional pages close; child frames terminate rendering, including non-network frames. Only the initial approved document navigation is admitted; reloads and other navigations are denied. Local assets are fetched without redirects and fulfilled only on status 200.

An initialization script disables Worker, SharedWorker, WebRTC constructors, WebTransport, WebSocketStream, service-worker registration and `window.open` before document scripts execute. CSP independently restricts workers, frames and connections. The asset server sends `X-DNS-Prefetch-Control: off`. Playwright 1.59.0 addresses the close-event teardown problem encountered during WebSocket testing.

Existing regression tests exercise guards independently of HTML sanitization/CSP, including WebSockets, workers, popups, frames, navigation and redirects. These are useful application controls, not proof that every native browser channel or exploit is contained. Compose gives the renderer network_mode: none, leaving only private loopback for trusted assets. gRPC uses a restricted Unix socket; stylesheet fetching remains in the API. Remote TCP deployments need equivalent independent network controls. [Playwright routing documentation](https://playwright.dev/java/docs/api/class-browsercontext#browser-context-route).

### 5. Local files and operating-system integration — isolated renderer implemented

Rendering runs in a separate UID 10002 container; the API uses UID 10001 and contains no Chromium/GraalJS. Renderer assets are root-owned/read-only, with private bounded temporary storage, cleared job environment, no credentials/host home mounts and no external network. Only a dedicated gRPC socket volume is shared; the API mounts it read-only with renderer-group access. Jobs have fresh JVMs and cannot read API code/cache through a shared filesystem.

This narrows filesystem exposure without removing external styles. A native compromise can still read renderer assets/current input and reach its gRPC socket. Unix permissions authenticate workloads, not individual requests. Keep the socket volume private and mount no unrelated data. Containers share the host kernel; stronger tenant isolation may require a sandboxed runtime or VM.

### 6. Native browser vulnerabilities and process isolation — substantially mitigated

Compose separates API and renderer containers/accounts, disables renderer networking and explicitly enables Chromium sandboxing. Read-only filesystems, dropped capabilities, no-new-privileges, private temporary storage, CPU/memory/PID limits and a scoped Chromium seccomp profile restrict the renderer. Both Chromium and GraalJS execute in disposable job JVMs supervised outside the job. Fatal jobs cause service exit and Compose restart to clear orphan native processes.

These controls reduce native exploit impact and remove render execution from the API JVM. They do not prove resistance to native/kernel vulnerabilities, bound concurrent API preparation or validate SVG. Verify effective sandbox/syscall/network controls on production hosts. Outside Compose, provide a restart supervisor and equivalent isolation. Maintain browser/JDK/dependency/base-image updates. [Playwright Docker guidance](https://playwright.dev/java/docs/docker).

### 7. Tampering with rendering machinery — input and independent output boundaries implemented

Input sanitization and CSP prevent the original supported route for submitted scripts to replace rendering functions or completion/output state (now grouped in `window.PermMathRenderer`). `GeneratedImageService` passes both renderer paths through `SvgOutputPolicy` in the API process before caching. The policy parses namespace-aware XML and permits presentation elements/attributes, the required outer foreignObject, XHTML/MathML and embedded glyph fragments. Unknown/active elements and attributes, external glyph references and unsafe hyperlink schemes reject the artifact. CSS is parsed and filtered by ph-css; hyperlink protocols use OWASP Java HTML Sanitizer's URL policy.

The ready flag still proves protocol completion, not document safety. The independent output policy provides that boundary. Standard JDK StAX disables DTDs, external entities and external resolution; processing instructions and entity references are rejected. Bounds cover 16 MiB input/output, depth 256, 100,000 elements, 64 attributes per element, eight namespace declarations per element and 32 KiB per style attribute. This does not bound rendering work already performed or API preparation; see 12–13.

### 8. Active content in returned SVG — reproduced path addressed

**Historical reproduction:** `<p>\(\href{javascript:alert(1)}{probe}\)</p>` previously retained a JavaScript URI in MathJax-generated SVG. The probe checked generated bytes; it did not demonstrate execution in a victim browser or origin. Browser regression tests now verify that JavaScript, file and data links are absent while HTTPS links and vector glyphs survive.

The template loads `tex-svg.js` as well as [AsciiMath](https://asciimath.org/), so TeX processing remains reachable in HTML text. MathJax's `ui/safe` extension is enabled in the browser and the rebuilt GraalJS bundle, with explicit protocols allowing HTTPS and denying HTTP, JavaScript, file and data. An additional MathJax render action applies safe filtering to [AsciiMath](https://asciimath.org/) trees; the upstream extension already installs TeX filtering. The API independently validates final output, so safety does not depend on the exporter or MathJax alone. Internal fragment references and HTTPS hyperlinks are retained; external resource references, active SVG/HTML and dangerous schemes are rejected. CSS resource URLs are removed except local fragments.

Both `/image` and `/img` return restrictive sandbox CSP, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. CSP permits embedded presentation styles while denying scripts, external resources, connections, frames and objects. Both cache identities include the new output-policy version; failed validation is not cached. Regression coverage includes forged renderer output, namespaces, active features, CSS URI escapes, XML entities, limits and delivery headers.

Compose now supplies a dedicated anonymous image listener. `permmath.image-host`
controls generated permalink origins independently of the public API host.
`/url`, `/url-encode` and Swagger remain publicly available on the API listener;
existing API `/image` and `/img` URLs redirect with HTTP 307 and preserve raw queries.
Only those two image routes allow GET/HEAD/OPTIONS on the image listener; other
routes/methods fail closed. Native nginx strips Cookie/Authorization/Proxy-Authorization
requests and hides Set-Cookie/WWW-Authenticate responses. Only the configured API
origin gets anonymous image CORS read access; credentialed CORS is not enabled.
OpenAPI uses the configured API origin in protected mode, supporting upstream TLS.

Compose requires a separate image origin and trusted ingress configuration.
Spring MVC checks the selected SVG handler for a marker written only by the trusted
image ingress, so permissive path normalization or forged client headers cannot
return SVG through the API delivery path. The API listener removes that marker;
the image listener overwrites it. Direct untrusted access redirects before input
decoding/rendering, including matrix-parameter image paths. The backend remains
private and both listeners share the existing client quotas and deadlines.

Production must map two HTTPS origins on different registrable domains to their
respective listeners and keep the image host anonymous without shared application
cookies. Default localhost ports test origin separation but cannot isolate cookie
scope. Domains, certificates, TLS ingress and production cookie policies are not
provisioned or verified here. Standalone/Eclipse mode retains the single-origin
fallback unless explicitly configured and does not provide independent delivery
isolation. Headers do not accompany downloaded/inline SVG everywhere, so maintain
the independent byte-level policy and bump its version when changed. This is a
presentation subset, not support for arbitrary active SVG.
[MathJax safe-extension documentation](https://docs.mathjax.org/en/v3.2/options/safe.html).

The HTML exporter now uses pinned html-to-image 1.11.13, built into a local bundle.
Its source adapter handles dimensions, disables font embedding and removes stylesheet
nodes and the library's obsolete foreignObject attribute. DOM cloning and computed-style
copying are delegated to the library. Existing browser network restrictions and API-side
SVG validation remain the security boundaries; html-to-image itself is not a sanitizer.
The HTML cache format is `html-svg-v8` and includes the exporter version.
Browser regressions cover external Bootstrap styles, safe math links, hostile input,
cross-request styles, vector positioning and viewing with the response CSP.

Handwritten JavaScript now lives in named source modules under `tools/svg`. MathJax
safety configuration is shared by both entry points; browser startup uses named async
functions, and guards are loaded independently from a bundled classpath resource.
Legacy unused `ASCIIMathML.js` and CDN loader resources are removed. ESLint, formatting
and generated-bundle checks are available through `npm run check`; they are development
checks and do not introduce a Node.js runtime. Input/output and network policies remain
the security boundaries.

After the JavaScript cleanup, the full browser-enabled Maven build passed (110 passed,
one Swagger UI test skipped). The additional MathJax script-load failure regression
also passed. Source lint/format checks and all three generated-bundle comparisons pass;
an intentionally stale artifact was correctly rejected by the bundle check.

### 9. Cross-request state — fresh execution state

Each HTML/formula job runs in a fresh JVM; Chromium contexts and GraalJS state are not reused across jobs. The API intentionally shares completed cached SVG by content identity. No cross-request leak was demonstrated. Do not add reusable execution state without isolation tests or mount other users' inputs/results into the renderer.

### 10. Browser permissions and interactive features — partially mitigated

No permissions are granted by the context setup; frames, forms, popups, navigation and downloads are restricted. These remove common interaction paths. There is no explicit comprehensive interactive-feature policy or Permissions-Policy header.

Retain deny-by-default permissions, add a restrictive Permissions Policy where relevant, and verify dialogs/interactive operations cannot stall the worker. Prioritize worker termination and isolation over assuming every browser API can be disabled with JavaScript.

### 11. Decompression and JSON denial of service — bounded individual inputs

`StringCompressionService` limits Base64 input to 11,206,656 characters, decoded
compressed bytes to 8 MiB + 16 KiB, and streamed GZIP expansion to 8 MiB in total,
including concatenated members. Compression applies the same uncompressed and
compressed bounds. The descriptor decoder limits JSON nesting to eight, tokens
to 128, property names to 64 characters, strings to 4,194,304 characters and
numbers to ten characters; trailing JSON is rejected. Malformed payloads return
HTTP 400 without including parser diagnostics in the public exception.

Shared descriptor construction validation applies to `/url`, `/image`
and HTML cache identities: nonblank content up to 4 MiB, inline CSS up to 1 MiB,
eight nonblank stylesheet references up to 2 KiB each, and width 220–4000.
`/img` validates a nonblank formula up to 256 KiB before rendering/cache identity
serialization. Text limits count UTF-8 bytes and preserve accepted whitespace.
Oversized existing permalinks are intentionally rejected. Proxy/server query
limits may be smaller. Tests cover byte boundaries, concatenated-member expansion,
compression bombs, malformed descriptors, endpoint rejection and no renderer call
on invalid inputs. Admission is implemented under 13; parsing/layout complexity budgets are implemented under 12.

The public `/decode` endpoint has been removed. Its bounded decompression/JSON
implementation remains necessary internally for `/image`. `/url-encode?text=...`
uses Java's UTF-8 URLEncoder on the servlet-decoded parameter, with the existing
4 MiB content byte cap (at most 12 MiB encoded output). It accepts empty input
and requires the parameter. It uses preparation admission, the unchanged 60s
request deadline and request quotas, and returns plain text with no-store,
nosniff and no-referrer. No renderer, generation quota or SVG cache is involved.
nginx exposes it only on the API listener; removed decode routes return 404.

### 12. Expensive layout, math and parsing — structural budgets implemented

Image width is validated from 220 to 4000, remote stylesheet downloads have limits, and submitted images/fonts/resource URLs/animations outside the CSS subset are removed. These are real improvements.

jsoup's streaming HTML parser uses its native stack-depth cap and aborts when the
element/node budgets are exhausted. HTML allows 20,000 elements, depth 128 and 64
attributes per element; normalized HTML is checked using jsoup's traversal with
a 60,000-node budget and 32 KiB inline styles. ph-css's own tokenizer enforces CSS
budgets before its recursive grammar runs, including escaped function tokens and
proper handling of strings/comments. CSS allows
20,000 rules/selectors, 100,000 declaration separators and 262,144 lexical tokens per source,
32 block/bracket levels, 64 function levels and 4,096 selector/token characters.
Combined stylesheets allow 20,000 rules/selectors; selectors multiplied by normalized HTML elements
must not exceed 100 million. Parsed numeric grid repeat counts are limited to 1,024.

MathJax discovers actual expressions and checks 2,048 expressions per HTML document
and 262,144 characters per expression before compilation. The formula endpoint
also retains its 256 KiB UTF-8 bound. Native browser TeX expansion allows 10,000
substitutions with a 262,144-character buffer. Compiled MathJax trees are checked
before typesetting: 100,000 nodes per document and depth 128. Parser errors,
including native expansion-limit failures, reject the render. The handwritten
math token/nesting and HTML delimiter scanners have been removed. [AsciiMath](https://asciimath.org/) has
no native pre-parse nesting limiter; isolated worker termination/memory controls
remain essential for that parser.

Expanded browser DOM and formula SVG are limited to 100,000 nodes and depth 256
before cloning/serialization. Viewport/content dimensions are at most 32,768 pixels
per side and 64 million pixels in area. Overflow and individual element geometry
are checked before export; element positions are bounded to one million pixels.
The API independently validates root SVG dimensions, 100,000 elements, depth 256
and 16 MiB output before caching. Cache policy versions were advanced.

These generous structural budgets reduce parser and export amplification; they do
not predict every CSS/MathJax layout cost. Layout occurs before geometry can be
measured. The isolated worker's 30-second deadline, 1 GiB heap and container
CPU/memory limits remain the hard execution boundary. API parser interruption
still cannot forcibly terminate non-cooperative code; admission retains its slot.
See README's complexity table for the maintained caps.

Defaults are defined once in `render-contract/src/main/resources/complexity-defaults.properties`.
`ComplexityPolicy` validates environment/JVM overrides, forwards resolved values
to isolated workers and injects them into both JavaScript engines. Use matching
settings for API and renderer; the resolved policy fingerprint versions cache keys.
There are no separate JavaScript limit defaults or custom HTML/CSS character lexers.

Verification after library/configuration cleanup: `mvn -o -Drendering.tests=true verify`
passed 149 tests (148 passed, one opt-in Swagger UI test skipped). Coverage includes
native token handling for comments/strings/escaped functions, jsoup implied closures
and depth budgets, override validation/precedence, worker-policy transfer and cache
fingerprints, configured caps reaching both JavaScript engines, compiled math-tree
limits, native TeX expansion rejection and MathJax expression discovery. Browser
tests retain rejection of a 40,000-pixel layout, acceptance of a 30,000-pixel document,
Bootstrap styling and healthy subsequent renders. JavaScript lint, formatting and
all generated-bundle checks passed.

### 13. Deadlines, queues and work cancellation — API admission and deadlines implemented

The unbounded single-thread rendering queue was removed. The renderer now has a
bounded Java FIFO queue before supervised child execution: one active worker,
16 waiting jobs and 15 seconds maximum wait by default. Retained input is charged
at three times serialized protobuf size plus 1 KiB per admitted job, including
running requests. The 64 MiB budget is additionally capped at 25% of supervisor
maximum heap; it does not account for every JVM/transport/output allocation.
Count/byte admission occurs before validation. Full admission returns gRPC
RESOURCE_EXHAUSTED / HTTP 503 with Retry-After and no-store; queue wait expiry
returns 504. API RPC budget defaults to 45 seconds, child execution remains at
most 30 seconds, and queue waiting consumes the original 60-second API deadline.
Expired/cancelled waiting jobs are removed and cannot execute later or restart
the container. Running cancellation interrupts the supervisor, which kills the
child and known descendants, retaining admission until actual worker exit.
Fatal running jobs restart the container to clear orphan native processes.
Both formula and HTML execution are covered.

The API now has configurable immediate admission: four input-preparation operations,
two unique image generations and two stylesheet batches by default, per instance.
API admission remains immediate; occupied capacity returns HTTP 503. Preparation covers
decompression/JSON decoding, URL creation and identity serialization; generation
covers HTML/CSS preparation, rendering and final SVG validation. Fetch admission
covers imports and is skipped for documents without stylesheet references.

A servlet-filter deadline defaults to 60 seconds and is propagated to workers.
Fetches use the smaller of their 15-second budget and the remaining API deadline;
gRPC uses the smaller of the rendering timeout and remaining API deadline. Expiry
returns HTTP 504. Duplicate cache waiters have bounded waits and cannot cancel
another caller's generation. Cache hits bypass generation/fetch admission but
still require decoding/identity preparation. Failed or timed-out loads are not cached.

Worker interruption and network/gRPC cancellation are attempted on timeout. Slots
are released only after the operation exits, so non-cooperative DNS/parsers cannot
create unlimited lingering tasks through repeated timeouts. They can still retain
slots and consume CPU within the API JVM; stronger termination guarantees require
process isolation. Native ingress connection and idle-delivery controls are now
implemented as described below; pre-header flood protection remains a deployment requirement.
Compose's independent API egress
restrictions are implemented under 3.

Per-client admission uses Bucket4j 8.16.0: 600 requests/minute with burst 120 and
32 concurrent HTTP requests, plus 60 cache-miss render attempts/minute with burst
10 and one concurrent generation. These limits are configurable under
`permmath.rate-limit`. All endpoints consume request tokens before decoding;
cached and shared in-flight results spend no generation tokens. Failed/rejected
render attempts spend tokens. Client generation slots release only when the actual
worker exits, and active request/worker leases pin limiter state beyond idle expiry.
Quota/concurrency rejection returns HTTP 429 with `Retry-After` and `no-store`;
global capacity rejection remains HTTP 503. The request concurrency cap bounds
servlet threads waiting for shared images without introducing a work queue.

Guava parses/canonicalizes literal IPs without DNS. Forwarding is disabled at the
framework/container level; only configured literal trusted peer IPs may supply a
single literal `X-Forwarded-For` address. Malformed, duplicate or missing trusted
headers fail HTTP 400; untrusted headers are ignored. Compose pins nginx's private
IP, trusts only that IP and overwrites incoming forwarding headers. Production
must verify the peer identity and any upstream NAT/load-balancer real-IP policy.
IPv4-mapped IPv6 identities normalize to IPv4, preventing alternate spellings from
granting another quota.

Caffeine state has an admission cap of 100,000 clients and 1h idle expiry by
default. Expiry must cover full refill; active state cannot expire. A full registry
rejects new identities without evicting existing clients and granting fresh
allowances. State is local/in-memory: restarts reset quotas, replicas multiply the
effective allowance, and NAT clients share a quota. A shared Bucket4j backend is
needed for fleet-wide enforcement. Distributed clients can still consume global
capacity; these controls are not authenticated-user fairness or volumetric DDoS
protection. The renderer's existing single-job global admission remains unchanged.

### 14. Cache abuse and persistence — partially mitigated

Caffeine has a configurable weight budget (256 MiB by default, capped at 25% of JVM maximum heap), estimates string/key memory, shares generation for identical keys and does not cache failed generations. HTML keys include `presentation-policy-3`; both HTML and formula keys now also include `svg-output-policy-2`, with updated format versions. Expiry is 60 days **after access**, so repeated hits can extend residency.

Renderer/API admission, per-client request/render quotas, 16 MiB output caps and final validation before storage bound artifact size and concurrent generation. Cache weight does not cap transient API allocations. External stylesheet contents can change without a URL/key change; define refresh/TTL semantics if freshness matters. Current storage is in-memory; future persistence would require purge/quarantine support.

### 15. Formula-only renderer abuse — supervised isolation and output policy implemented

AsciiMathRenderer passes formula as a value and disables Java host access/class lookup. It now runs in a fresh disposable JVM inside the renderer container. Formula input/output are capped at 16 MiB, job heap at 1 GiB, and the supervisor terminates stalled jobs under the RPC/service deadline. Container limits cover native allocations. /img still launches neither Chromium nor external CSS fetching.

This removes unbounded formula execution from the API process. The common API-side SVG policy applies to `/img` as well as styled HTML, and its GraalJS bundle now uses MathJax safe filtering. API admission and deadlines are implemented (13); complexity budgets are implemented (12); non-cooperative API work still needs attention (13). Individual inputs are bounded (11). The TeX-link probe targeted styled HTML; it is not evidence of that exact payload working in [AsciiMath](https://asciimath.org/)-only /img.

### 16. Data exposure and supply chain — partially mitigated; ongoing operational work

Endpoint logging records the request path, handler, status/error type and duration rather than query payloads. Cache logging uses hashed identities. Nevertheless, compressed permalinks and plain formula queries expose content to browser history, reverse-proxy/access logs and monitoring unless those systems redact them. Compression provides no confidentiality.

Dependencies have declared versions, and browser installation uses the selected Playwright version. This reassessment did not perform a complete CVE scan or establish that dependencies are vulnerability-free. Establish automated dependency/browser advisory checks, reproducible artifact verification and timely updates. Use an immutable runtime image with browser binaries unwritable by the runtime account. Add referrer controls and document permalink privacy. If persistence is added, use server-generated identifiers, quotas, atomic writes and safe database/XML handling.

## Local container verification

The original API/renderer build passes Maven verification on Docker Desktop's Linux engine. Both formula and externally fetched Bootstrap-styled HTML returned SVG through the Unix gRPC transport. Inspection confirmed renderer UID 10002, API UID 10001 with supplementary socket group, network mode none with only loopback, socket mode 0660, read-only root and configured resource/security controls. A private raw gRPC probe stalled Chromium deliberately: with a three-second service limit it returned DEADLINE_EXCEEDED in about 3.6 seconds, the renderer restart count increased, and a subsequent uncached formula request succeeded. This verifies the local setup; production host controls remain unverified.

The subsequent four-service deployment passed the reproducible
`scripts/test-egress.ps1` smoke tests on Docker Engine 28.5.1 with Squid 7.6:
isolated API network membership, absent external default route, direct internet
denial, public HTTPS CONNECT success, forbidden address/port rejection and mixed
public/private DNS rejection. Formula and externally fetched Bootstrap-styled HTML
returned SVG through nginx with presentation styles and delivery CSP intact.
Browser-enabled Maven verification ran 152 tests: 151 passed and one opt-in
Swagger UI test skipped. The final proxy-routing adjustment also passed all three
focused proxy tests and the rebuilt deployment smoke tests. These checks cover
local behavior; production infrastructure and a complete native-proxy security
audit remain outside this verification.

## Payload-limit and cache-headroom verification

After the initial payload-limit and cache changes, `mvn -o -Drendering.tests=true verify` passed: 124 tests, 123 passed and one opt-in Swagger UI test skipped. Coverage includes both browser suites, malformed/truncated compression, concatenated-member expansion, compression bombs on both compressed endpoints, UTF-8 field boundaries, invalid descriptors and heap-relative cache budgets. `git diff --check` passed. The Docker cache default was updated in source; container runtime memory behavior was not re-measured in this change.

The subsequent increase to 256 KiB formulas, 4 MiB HTML, 1 MiB inline CSS, 8 MiB JSON and 16 MiB renderer/SVG payloads passed `mvn -o verify`: 112 passed, 12 opt-in browser/Swagger tests skipped. Compression allowances and JSON string constraints were aligned, and UTF-8 rejection tests use the new boundaries.

## API admission and deadline verification

API admission/deadline verification passed with browser testing enabled:
134 tests, 133 passed and one opt-in Swagger UI test skipped. Tests cover overload
before input preparation, decoding timeouts, slot retention for non-cooperative
work, recovery after cancellation, cache-hit availability during generation
overload, duplicate wait deadlines and remaining-budget propagation into gRPC.
Both browser suites, including external Bootstrap styles, passed. A subsequent
renderer startup regression passed with the readiness marker on stdout, allowing
Eclipse launch groups to proceed to API startup.

## Client rate-limit verification

Final browser-enabled `mvn -o -Drendering.tests=true verify` passed: 175 tests,
174 passed and one opt-in Swagger UI test skipped. The 23 new regressions cover
request/render quota isolation and refill, trusted-header validation, startup
configuration, request and worker concurrency, worker retention after request
closure, bounded state admission/expiry, shared in-flight cache results, cached
image availability after render-token exhaustion, and shared HTML/formula quotas.

The rebuilt Linux API/ingress passed both Docker smoke scripts on the separate
test subnet: public Bootstrap styles and formulas render, direct egress and
forbidden/mixed DNS destinations remain blocked, and changing forwarded headers
through nginx cannot bypass request/render quotas. HTTP 429 includes positive
`Retry-After` and `no-store`; cached SVG retains its CSP. Dynamic Docker addresses
are allocated outside nginx's fixed trusted IP range. Normal and test Compose
configurations and `git diff --check` passed. Temporary test containers/networks
were removed. Production peer identity, NAT policy and replica-wide quotas still
require deployment-specific verification. Renderer queuing was implemented subsequently (13).

## Image-origin delivery verification

Browser-enabled Maven verification after the boundary/OpenAPI changes passed:
189 tests, 188 passed and one opt-in Swagger UI test skipped. New configuration
and MVC regressions cover origin validation, raw redirect queries, public permalink
creation, trusted/forged/duplicate markers, matrix-parameter paths, required startup
configuration and retained SVG/OpenAPI headers.

The separate Compose deployment passed delivery and egress smoke tests: image-only
routes, method/CORS restrictions, cross-origin legacy redirects, public `/url`,
actual SVG headers, Bootstrap styling and independent egress restrictions. A native
nginx upstream fixture confirms credential-header removal and response-cookie
suppression on both image endpoints. An additional opt-in Chromium integration
test passed against the running deployment: anonymous API-to-image fetches render
formula and HTML SVG, while credentialed cross-origin reads are rejected by the
browser. Direct private-API probes reject forged markers and matrix-parameter
paths; the existing rate-limit smoke test also passes after cross-origin redirects,
including cached SVG availability. Compose validation and `git diff --check` pass;
temporary fixtures and the test deployment were removed. This verifies local two-origin behavior; production domain/cookie/TLS
isolation still requires deployment-specific checks. Renderer queuing was implemented subsequently (13).

## Recommended next work

1. Maintain decompression and endpoint validation regressions (11); individual payload limits are implemented.
2. Maintain the implemented DOM/CSS/math and dimension budgets (12) and bounded renderer queue/cancellation/deadline regressions (13); non-cooperative API work may still need process isolation. Review memory and shared client quotas before scaling admission or replicas.
3. Verify isolated renderer and implemented API/proxy network controls on production hosts (3–6); maintain proxy ACL/package regressions.
4. Verify the implemented delivery-origin boundary with production domains/cookies/TLS and maintain operational patch/privacy policies (8, 16); define external-style freshness semantics and shared rate-limit state if replicas are deployed (13–14).

The target architecture remains an API, a controlled stylesheet fetcher and a restricted rendering worker. It preserves external styling while putting limits and independent validation around untrusted content.

### Ingress connection and slow-client controls

The nginx ingress now uses native shared connection zones across the API and
image listeners: 64 active requests per socket client IP and 128 total by default.
Client-supplied forwarding headers cannot change the quota key. Excess admitted
requests return 429 with Retry-After: 1, retaining image CORS headers. Header
completion is bounded to 15 seconds; body-read and client-response-write idle
limits are 30 seconds, and idle keepalive closes after 15 seconds. Limits are
configurable via PERMMATH_INGRESS_* settings documented in docker/README.md;
non-positive/non-integer settings fail startup. Existing large permalink headers
and the API's 60-second processing deadline remain supported. This ingress change
did not add queuing; the later renderer queue is described under 13.

Residual: nginx limit_conn starts accounting only after complete headers. Partial
headers expire, but fast connection floods and concurrent large header allocations
can still exhaust ingress sockets/memory (the retained two 12 MiB header buffers
can exceed the 256 MiB container budget in aggregate). Production front-door/TLS
infrastructure must enforce connection admission before HTTP header parsing and
budget header memory. Idle body/write deadlines do not terminate traffic that
continues making progress. NAT/replicas require deployment-specific quota review.

Validation: the production ingress image passed native Docker smoke coverage for
an 11 MiB request line, 8 MiB body cap, shared per-client and total connection
limits, spoofed forwarded headers, Retry-After/image CORS, header/body/keepalive
and slow-response-reader deadlines, recovery and invalid configuration rejection.
Compose validation and git diff whitespace checks passed. Test-only containers
and networks were removed; no production deployment was performed.

## Renderer queue verification

Browser-enabled Maven verification passed: 202 tests, 200 passed and two opt-in
UI/delivery tests skipped. Twelve new regressions cover fixed-worker FIFO execution,
count and retained-byte saturation, admission recovery, queue/request deadline expiry,
queued cancellation without later execution, non-cooperative running slot retention,
shutdown, configuration/heap headroom, real child-JVM cancellation/termination,
and RPC expiry/cancellation without restarting or interrupting active jobs.
Existing per-client, cache sharing, overload, parser and browser suites also passed.

Fresh Linux API/renderer images passed Docker verification and an isolated Compose
smoke deployment. Two independent client identities requested distinct uncached
formulas simultaneously: both returned SVG, with one waiting behind the other.
Delivery smoke coverage preserved public URLs, image-origin redirects, CORS/security
headers, private-API marker enforcement and credential/cookie isolation. Compose
validation and git diff whitespace checks passed. Test clients, fixtures and the
separate Compose deployment were removed.

The API keeps its existing two unique-generation slots, including renderer waiting;
client generation slots remain occupied during the RPC. This protects API preparation
memory rather than automatically raising admission to fill all 16 queue slots.
Capacity/byte/wait limits are configurable; raising API admission or adding replicas
requires reviewing preprocessing memory and shared client quotas. The input-retention
budget is conservative accounting, not a proof of total heap consumption. HTTP client
disconnects do not guarantee immediate servlet cancellation; the propagated API/RPC
deadlines still bound waiting. Non-cooperative API parsing remains a separate issue.

## URL encoding and decode-endpoint cleanup verification

The public decode controller, OpenAPI operation and nginx proxy route are removed;
its bounded internal descriptor decoder remains in use by /image. GET /url-encode
accepts a required text parameter and applies Java UTF-8 query-value encoding,
preserving empty input and escaping literal plus/percent/reserved characters.
Existing preparation admission, request quotas, deadlines and input byte bounds
apply. README, container documentation and smoke fixtures now use the new route.

Browser-enabled Maven verification passed: 205 tests, 203 passed and two opt-in
UI/delivery tests skipped. Coverage includes raw-query decoding exactly once,
Unicode/whitespace, the 4 MiB UTF-8 input and 12 MiB worst-case output boundary,
missing/oversized inputs, removed routes/OpenAPI, preparation overload/deadline
recovery and independent request/render quotas. Compressed-image decompression,
JSON validation and bomb rejection remain covered through /image. Linux image
verification caught and fixed a PID-file publication race in the existing process
cancellation test fixture; atomic publication then passed on Windows and Linux.

The rebuilt API/ingress passed native Docker checks for encoding/plain-text headers,
removed decode routes, image-origin routing/CORS/credential isolation, concurrent
uncached renderer queueing, quota/spoof resistance and cached-SVG availability.
The updated ingress smoke test also passed large request-line, body, connection,
header/body/keepalive and slow-reader bounds. Compose and whitespace validation
passed. Temporary probe containers, networks and the test deployment were removed.
Renderer isolation, queue limits, cache budgets, container sizes and production
origin/network requirements were preserved; no production deployment was performed.
