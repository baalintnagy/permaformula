# Security reassessment — 2026-10-06

Reviewed the current Maven modules, rendering policies, gRPC service and Docker configuration, including the SVG output hardening following commit `bebf5b6`. This replaces the original assessment; finding numbers are retained for continuity. Removed JavaFX functionality is excluded.

This is a source review with focused local rendering probes, not a penetration test, a full dependency vulnerability scan or verification of production infrastructure. Deployment controls outside this repository remain unverified. Browser-enabled Maven verification: 111 tests, 110 passed and one opt-in Swagger UI test skipped, including output-policy and response-header regressions. Existing coverage includes gRPC transport/cancellation, correlation and supervised process termination/recovery. Styled SVG is also checked visually and in Chromium both as an image and as a document with the delivery CSP.

The submitted HTML/CSS execution paths have been substantially hardened, and external styles remain supported through controlled fetching. Generated SVG now passes an independent API-side presentation policy before caching or delivery. The most urgent remaining issues are **unbounded decompression (11), and API preparation/fetch admission and parsing complexity (12–13)**.

## Findings

### 1. JavaScript execution through submitted HTML — addressed for submitted markup

`RenderContentPolicy` applies OWASP Java HTML Sanitizer before Chromium parses the document. The allowlist permits presentation HTML and limited attributes, excludes scripts, event handlers, submitted SVG, frames, forms and links, and sanitizes inline CSS. The trusted template receives nonce-based CSP; only trusted MathJax/exporter scripts are intended to execute.

The original raw-markup execution path is no longer present. MathJax-generated DOM and final SVG have separate controls under 8. Keep hostile-markup regression coverage when expanding the supported HTML subset.

### 2. Breaking out of the stylesheet — addressed for submitted CSS

`StylesheetPolicy` parses CSS, restricts properties and value functions, rejects resource URLs and unsupported rules, and escapes literal `<` before stylesheet embedding. `RenderContentPolicy` builds the document with DOM APIs rather than substituting raw input into the template. Inline declarations use the same policy. External CSS is also sanitized.

The original closing-style-tag injection path is addressed. CSS parsing/layout resource consumption is a separate open issue under 12.

### 3. SSRF against internal services — substantially mitigated; deployment boundary pending

Chromium has a deny-by-default request policy: the approved document and a narrow set of trusted local scripts are permitted. CSS is fetched outside Chromium by `StylesheetFetcher`, preserving `styleRefs` and supported imports.

The fetcher requires HTTPS on port 443, rejects credentials/fragments and inappropriate destinations, validates all DNS answers and supplies those validated addresses to the actual HTTP connection. Redirects are manually revalidated. TLS verification remains enabled; automatic redirects, retries, cookies and response compression are disabled. CSS MIME type and streamed body size are checked.

Current bounds: eight top-level references, 16 resources including imports, import depth three, three redirects per resource, 1 MiB per response and 4 MiB combined decoded CSS measured after UTF-8 re-encoding. Requests share a 15-second network deadline with five-second connection/socket/response timeouts.

Compose disables renderer networking; external CSS remains fetched in the API. Remaining: no independent API egress restriction is defined. Arbitrary public HTTPS hosts are allowed, so the API can still be abused as a bounded public-site fetcher. Apply fetch concurrency/rate limits; add a domain allowlist if product requirements permit it. Use network rules to reject internal/metadata destinations independently of application checks. Blocking DNS resolution and CSS processing are not proven to obey the network cancellation deadline; see 13. [OWASP SSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

### 4. Alternative browser network channels — application guards and isolated renderer network boundary implemented

`BrowserChannelPolicy` uses a fresh context with service workers blocked, downloads disabled and context-wide HTTP/WebSocket handling. WebSockets never connect to their destination. Additional pages close; child frames terminate rendering, including non-network frames. Only the initial approved document navigation is admitted; reloads and other navigations are denied. Local assets are fetched without redirects and fulfilled only on status 200.

An initialization script disables Worker, SharedWorker, WebRTC constructors, WebTransport, WebSocketStream, service-worker registration and `window.open` before document scripts execute. CSP independently restricts workers, frames and connections. The asset server sends `X-DNS-Prefetch-Control: off`. Playwright 1.59.0 addresses the close-event teardown problem encountered during WebSocket testing.

Existing regression tests exercise guards independently of HTML sanitization/CSP, including WebSockets, workers, popups, frames, navigation and redirects. These are useful application controls, not proof that every native browser channel or exploit is contained. Compose gives the renderer network_mode: none, leaving only private loopback for trusted assets. gRPC uses a restricted Unix socket; stylesheet fetching remains in the API. Remote TCP deployments need equivalent independent network controls. [Playwright routing documentation](https://playwright.dev/java/docs/api/class-browsercontext#browser-context-route).

### 5. Local files and operating-system integration — isolated renderer implemented

Rendering runs in a separate UID 10002 container; the API uses UID 10001 and contains no Chromium/GraalJS. Renderer assets are root-owned/read-only, with private bounded temporary storage, cleared job environment, no credentials/host home mounts and no external network. Only a dedicated gRPC socket volume is shared; the API mounts it read-only with renderer-group access. Jobs have fresh JVMs and cannot read API code/cache through a shared filesystem.

This narrows filesystem exposure without removing external styles. A native compromise can still read renderer assets/current input and reach its gRPC socket. Unix permissions authenticate workloads, not individual requests. Keep the socket volume private and mount no unrelated data. Containers share the host kernel; stronger tenant isolation may require a sandboxed runtime or VM.

### 6. Native browser vulnerabilities and process isolation — substantially mitigated

Compose separates API and renderer containers/accounts, disables renderer networking and explicitly enables Chromium sandboxing. Read-only filesystems, dropped capabilities, no-new-privileges, private temporary storage, CPU/memory/PID limits and a scoped Chromium seccomp profile restrict the renderer. Both Chromium and GraalJS execute in disposable job JVMs supervised outside the job. Fatal jobs cause service exit and Compose restart to clear orphan native processes.

These controls reduce native exploit impact and remove render execution from the API JVM. They do not prove resistance to native/kernel vulnerabilities, bound API preparation or validate SVG. Verify effective sandbox/syscall/network controls on production hosts. Outside Compose, provide a restart supervisor and equivalent isolation. Maintain browser/JDK/dependency/base-image updates. [Playwright Docker guidance](https://playwright.dev/java/docs/docker).

### 7. Tampering with rendering machinery — input and independent output boundaries implemented

Input sanitization and CSP prevent the original supported route for submitted scripts to replace `window.exportStyledSvg`, `window.renderState` or `window.renderSvg`. `GeneratedImageService` now passes both renderer paths through `SvgOutputPolicy` in the API process before caching. The policy parses namespace-aware XML and permits presentation elements/attributes, the required outer foreignObject, XHTML/MathML and embedded glyph fragments. Unknown/active elements and attributes, external glyph references and unsafe hyperlink schemes reject the artifact. CSS is parsed and filtered by ph-css; hyperlink protocols use OWASP Java HTML Sanitizer's URL policy.

The ready flag still proves protocol completion, not document safety. The independent output policy provides that boundary. Standard JDK StAX disables DTDs, external entities and external resolution; processing instructions and entity references are rejected. Bounds cover 8 MiB input/output, depth 128, 20,000 elements, 64 attributes per element, eight namespace declarations per element and 32 KiB per style attribute. This does not bound rendering work already performed or API preparation; see 12–13.

### 8. Active content in returned SVG — reproduced path addressed

**Historical reproduction:** `<p>\(\href{javascript:alert(1)}{probe}\)</p>` previously retained a JavaScript URI in MathJax-generated SVG. The probe checked generated bytes; it did not demonstrate execution in a victim browser or origin. Browser regression tests now verify that JavaScript, file and data links are absent while HTTPS links and vector glyphs survive.

The template loads `tex-svg.js` as well as ASCIIMath, so TeX processing remains reachable in HTML text. MathJax's `ui/safe` extension is enabled in the browser and the rebuilt GraalJS bundle, with explicit protocols allowing HTTPS and denying HTTP, JavaScript, file and data. An additional MathJax render action applies safe filtering to ASCIIMath trees; the upstream extension already installs TeX filtering. The API independently validates final output, so safety does not depend on the exporter or MathJax alone. Internal fragment references and HTTPS hyperlinks are retained; external resource references, active SVG/HTML and dangerous schemes are rejected. CSS resource URLs are removed except local fragments.

Both `/image` and `/img` return restrictive sandbox CSP, `X-Content-Type-Options: nosniff` and `Referrer-Policy: no-referrer`. CSP permits embedded presentation styles while denying scripts, external resources, connections, frames and objects. Both cache identities include the new output-policy version; failed validation is not cached. Regression coverage includes forged renderer output, namespaces, active features, CSS URI escapes, XML entities, limits and delivery headers.

A dedicated delivery origin without application cookies remains a deployment improvement. Headers do not accompany downloaded/inline SVG everywhere, so maintain the independent byte-level policy and bump its version when changed. This is a presentation subset, not support for arbitrary active SVG. [MathJax safe-extension documentation](https://docs.mathjax.org/en/v3.2/options/safe.html).

### 9. Cross-request state — fresh execution state

Each HTML/formula job runs in a fresh JVM; Chromium contexts and GraalJS state are not reused across jobs. The API intentionally shares completed cached SVG by content identity. No cross-request leak was demonstrated. Do not add reusable execution state without isolation tests or mount other users' inputs/results into the renderer.

### 10. Browser permissions and interactive features — partially mitigated

No permissions are granted by the context setup; frames, forms, popups, navigation and downloads are restricted. These remove common interaction paths. There is no explicit comprehensive interactive-feature policy or Permissions-Policy header.

Retain deny-by-default permissions, add a restrictive Permissions Policy where relevant, and verify dialogs/interactive operations cannot stall the worker. Prioritize worker termination and isolation over assuming every browser API can be disabled with JavaScript.

### 11. Decompression and JSON denial of service — open, high priority

`StringCompressionService.decompress` decodes the whole Base64 value and uses unbounded `GZIPInputStream.readAllBytes()`. `/image` and `/decode` decompress before descriptor validation. A small compressed query can therefore cause large allocations despite HTTP query-size limits. `/decode` does not invoke the rendering service's descriptor validation.

Bound encoded input, compressed bytes and decompressed bytes while reading; then apply service-specific JSON depth/string/list and descriptor limits consistently to both paths. Jackson has default parser constraints, but those do not bound the preceding GZIP expansion. Reject over-limit input before cache-key serialization or rendering. Also bound `/url` and `/img` input sizes.

### 12. Expensive layout, math and parsing — partially mitigated, high-priority gaps

Image width is validated from 220 to 4000, remote stylesheet downloads have limits, and submitted images/fonts/resource URLs/animations outside the CSS subset are removed. These are real improvements.

Prepared HTML/formula and SVG output are capped at 8 MiB at the RPC/process boundary; execution has supervised deadlines and container resource limits. API input before preparation, DOM node count, inline CSS size, rule/selector complexity and rendered height remain insufficiently bounded. Allowed CSS can still create expensive layouts and extreme dimensions. CSS nesting checks occur after parsing and are not a parser resource boundary. MathJax-generated DOM can also grow substantially. Export copies the DOM and computed styles and holds several representations in memory.

Add limits before parsing/rendering, enforce height/output limits, and bound total work with worker CPU/memory limits. Reject oversized output before returning/caching it; checking cache weight after generation is too late.

### 13. Deadlines, queues and work cancellation — rendering bounded; API preparation gaps remain

The unbounded single-thread rendering queue is removed. SupervisedRenderer uses immediate bounded admission (one job by default), returning overload as gRPC RESOURCE_EXHAUSTED / HTTP 503. RPC deadlines are bounded by the service's 30-second maximum. Cancellation interrupts the supervisor, which kills the child and known descendants. Both formula and HTML execution are covered. Fatal jobs restart the container to clear orphan native processes. Process termination/recovery and RPC cancellation have regression tests.

HTML sanitization, external fetching and CSS parsing still run in the API before the RPC deadline. HTTP concurrency, unique cache misses and fetch/preparation admission are not bounded here. Blocking DNS/CSS parsing are not proven to stop under the stylesheet network timer. Add end-to-end preparation budgets, input/complexity limits and API admission controls.

### 14. Cache abuse and persistence — partially mitigated

Caffeine has a configurable weight budget (1 GB by default), estimates string/key memory, shares generation for identical keys and does not cache failed generations. HTML keys include `presentation-policy-3`; both HTML and formula keys now also include `svg-output-policy-1`, with updated format versions. Expiry is 60 days **after access**, so repeated hits can extend residency.

Renderer admission, 8 MiB output caps and final validation before storage bound artifact size and reject unsafe output, but unique cache misses still cause unbounded API preparation pressure; cache weight does not cap transient API allocations. Add miss rate/admission limits. External stylesheet contents can change without a URL/key change; define refresh/TTL semantics if freshness matters. Current storage is in-memory; future persistence would require purge/quarantine support.

### 15. Formula-only renderer abuse — supervised isolation and output policy implemented

AsciiMathRenderer passes formula as a value and disables Java host access/class lookup. It now runs in a fresh disposable JVM inside the renderer container. Formula input/output are capped at 8 MiB, job heap at 512 MiB, and the supervisor terminates stalled jobs under the RPC/service deadline. Container limits cover native allocations. /img still launches neither Chromium nor external CSS fetching.

This removes unbounded formula execution from the API process. The common API-side SVG policy applies to `/img` as well as styled HTML, and its GraalJS bundle now uses MathJax safe filtering. API input/admission still need work (11–13). The TeX-link probe targeted styled HTML; it is not evidence of that exact payload working in ASCIIMath-only /img.

### 16. Data exposure and supply chain — partially mitigated; ongoing operational work

Endpoint logging records the request path, handler, status/error type and duration rather than query payloads. Cache logging uses hashed identities. Nevertheless, compressed permalinks and plain formula queries expose content to browser history, reverse-proxy/access logs and monitoring unless those systems redact them. Compression provides no confidentiality.

Dependencies have declared versions, and browser installation uses the selected Playwright version. This reassessment did not perform a complete CVE scan or establish that dependencies are vulnerability-free. Establish automated dependency/browser advisory checks, reproducible artifact verification and timely updates. Use an immutable runtime image with browser binaries unwritable by the runtime account. Add referrer controls and document permalink privacy. If persistence is added, use server-generated identifiers, quotas, atomic writes and safe database/XML handling.

## Local container verification

The two-image build passes Maven verification on Docker Desktop's Linux engine. Both formula and externally fetched Bootstrap-styled HTML returned SVG through the Unix gRPC transport. Inspection confirmed renderer UID 10002, API UID 10001 with supplementary socket group, network mode none with only loopback, socket mode 0660, read-only root and configured resource/security controls. A private raw gRPC probe stalled Chromium deliberately: with a three-second service limit it returned DEADLINE_EXCEEDED in about 3.6 seconds, the renderer restart count increased, and a subsequent uncached formula request succeeded. This verifies the local setup; production host controls remain unverified.

## Recommended next work

1. Bound decompression and all entry-point payloads, including `/decode` (11).
2. Bound API preparation/fetch admission and input/DOM/CSS/math complexity and rendered height (12–13). Render deadlines, admission and output caps are implemented.
3. Verify isolated renderer controls on production hosts and add independent API-fetcher egress controls (3–6).
4. Add delivery-origin isolation, cache-miss controls and operational patch/privacy policies (8, 14, 16).

The target architecture remains an API, a controlled stylesheet fetcher and a restricted rendering worker. It preserves external styling while putting limits and independent validation around untrusted content.
