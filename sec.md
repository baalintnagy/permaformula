# Security reassessment — 2026-10-06

Reviewed application code at commit `728f740`, rendering policies, dependency declarations and Docker configuration. This replaces the original assessment; finding numbers are retained for continuity. Removed JavaFX functionality is excluded.

This is a source review with a focused local rendering probe, not a penetration test, a full dependency vulnerability scan or verification of production infrastructure. Deployment controls outside this repository remain unverified. Prior implementation verification recorded 76 tests (75 passed, one skipped), followed by six passing browser-channel tests after the final guard change; the full suite was not rerun for this documentation update.

The submitted HTML/CSS execution paths have been substantially hardened, and external styles remain supported through controlled fetching. The most urgent remaining issues are **unsafe MathJax-generated SVG links (8), unbounded decompression (11), expensive rendering and unbounded queues (12–13, 15), and insufficient process isolation (6)**.

## Findings

### 1. JavaScript execution through submitted HTML — addressed for submitted markup

`RenderContentPolicy` applies OWASP Java HTML Sanitizer before Chromium parses the document. The allowlist permits presentation HTML and limited attributes, excludes scripts, event handlers, submitted SVG, frames, forms and links, and sanitizes inline CSS. The trusted template receives nonce-based CSP; only trusted MathJax/exporter scripts are intended to execute.

The original raw-markup execution path is no longer present. This does not establish that DOM created later by MathJax, or the exported SVG, is safe: see 8. Keep hostile-markup regression coverage when expanding the supported HTML subset.

### 2. Breaking out of the stylesheet — addressed for submitted CSS

`StylesheetPolicy` parses CSS, restricts properties and value functions, rejects resource URLs and unsupported rules, and escapes literal `<` before stylesheet embedding. `RenderContentPolicy` builds the document with DOM APIs rather than substituting raw input into the template. Inline declarations use the same policy. External CSS is also sanitized.

The original closing-style-tag injection path is addressed. CSS parsing/layout resource consumption is a separate open issue under 12.

### 3. SSRF against internal services — substantially mitigated; deployment boundary pending

Chromium has a deny-by-default request policy: the approved document and a narrow set of trusted local scripts are permitted. CSS is fetched outside Chromium by `StylesheetFetcher`, preserving `styleRefs` and supported imports.

The fetcher requires HTTPS on port 443, rejects credentials/fragments and inappropriate destinations, validates all DNS answers and supplies those validated addresses to the actual HTTP connection. Redirects are manually revalidated. TLS verification remains enabled; automatic redirects, retries, cookies and response compression are disabled. CSS MIME type and streamed body size are checked.

Current bounds: eight top-level references, 16 resources including imports, import depth three, three redirects per resource, 1 MiB per response and 4 MiB combined decoded CSS measured after UTF-8 re-encoding. Requests share a 15-second network deadline with five-second connection/socket/response timeouts.

Remaining: no OS-level egress restriction is defined. Arbitrary public HTTPS hosts are allowed, so the API can still be abused as a bounded public-site fetcher. Apply fetch concurrency/rate limits; add a domain allowlist if product requirements permit it. Use network rules to reject internal/metadata destinations independently of application checks. Blocking DNS resolution and CSS processing are not proven to obey the network cancellation deadline; see 13. [OWASP SSRF guidance](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

### 4. Alternative browser network channels — application guards implemented; OS boundary pending

`BrowserChannelPolicy` uses a fresh context with service workers blocked, downloads disabled and context-wide HTTP/WebSocket handling. WebSockets never connect to their destination. Additional pages close; child frames terminate rendering, including non-network frames. Only the initial approved document navigation is admitted; reloads and other navigations are denied. Local assets are fetched without redirects and fulfilled only on status 200.

An initialization script disables Worker, SharedWorker, WebRTC constructors, WebTransport, WebSocketStream, service-worker registration and `window.open` before document scripts execute. CSP independently restricts workers, frames and connections. The asset server sends `X-DNS-Prefetch-Control: off`. Playwright 1.59.0 addresses the close-event teardown problem encountered during WebSocket testing.

Existing regression tests exercise guards independently of HTML sanitization/CSP, including WebSockets, workers, popups, frames, navigation and redirects. These are useful application controls, not proof that every native browser channel or exploit is contained. A restricted worker needs outbound TCP/UDP/DNS rules; keep controlled external stylesheet fetching outside that worker. [Playwright routing documentation](https://playwright.dev/java/docs/api/class-browsercontext#browser-context-route).

### 5. Local files and operating-system integration — partially mitigated

Submitted active markup, unsupported resource URLs and browser downloads are blocked. Normal submitted content has no supported filesystem or shell interface.

However, Chromium still runs under the deployment account beside application files, without a separate restricted worker. Application routing is not filesystem containment following a native exploit. Run with a minimal environment, read-only trusted assets, a private bounded temporary directory and no credentials, home-directory mounts or host sockets. Do not infer those restrictions from project-local browser installation.

### 6. Native browser vulnerabilities and process isolation — open, high priority

The reassessment probe at `728f740` ran Chromium with `--no-sandbox`. Subsequent container preparation adds an explicit `chromium-sandbox` setting enabled in Docker, an unprivileged runtime user, a separate JRE runtime stage, non-writable application/browser assets, and a Compose configuration with read-only root filesystem, private temporary storage, dropped capabilities, seccomp and CPU/memory/process limits. Local development retains the previous sandbox default. See `docker/README.md` for configuration and validation requirements.

These controls are prepared in the repository; effective Linux sandbox/container behavior must be verified on the deployment host. The API and renderer still share a container/account. No separate rendering worker or OS egress policy is implemented, and container limits do not impose per-request deadlines or prevent all denial of service.

Container preparation was smoke-tested on Docker Desktop's Linux engine: the image built successfully with 77 tests (67 passed, 10 opt-in browser tests skipped); `/img` and `/image` returned SVG successfully, including externally fetched Bootstrap styling. Runtime inspection confirmed UID 10001, non-writable application/browser files, read-only root filesystem, configured memory/CPU/process limits, no-new-privileges and seccomp. Chromium launched without `--no-sandbox` and its processes showed active seccomp filtering. This verifies the local configuration, not every production host or workload.

Use an unprivileged isolated worker, explicitly enable and verify the Chromium sandbox on the target platform, restrict filesystem/syscalls/network access, and impose CPU/memory/process/temp-storage limits. A separate browser process under the API account is insufficient containment. Maintain a browser/JDK/dependency patch process. [Playwright sandbox option](https://playwright.dev/docs/api/class-browsertype#browser-type-launch), [Playwright Docker guidance](https://playwright.dev/java/docs/docker).

### 7. Tampering with rendering machinery — original script path mitigated; output trust remains open

Input sanitization and CSP prevent the original supported route for submitted scripts to replace `window.exportStyledSvg`, `window.renderState` or `window.renderSvg`. The service still accepts the page's ready flag and SVG string without an independent final-output validator.

Validate output outside the page before caching or serving it. The ready flag proves completion according to the renderer protocol, not safety of the resulting document. See the confirmed output gap in 8.

### 8. Active content in returned SVG — confirmed open, high priority

**Local reproduction:** render `<p>\(\href{javascript:alert(1)}{probe}\)</p>` through `ImageRendererService` with the current template and policies. The exported SVG retains `javascript:alert(1)` in a MathJax-generated link. The probe checked the generated bytes; it did not demonstrate execution in a victim browser or origin.

The template loads `tex-svg.js` as well as ASCIIMath, so TeX processing remains reachable in HTML text. MathJax creates the link after input sanitization. `export-svg.js` removes scripts and event attributes but does not reject unsafe URI attributes. `GeneratedImageService` stores the output without final validation. The rendering page's CSP is not carried into the returned SVG document.

Exploitability depends on how the SVG is consumed: an image-element context has different restrictions from direct document opening or inline embedding. Nevertheless, retaining a supplied JavaScript URI is an output-policy failure. `/image` and `/img` currently set SVG content type but no restrictive CSP, `nosniff` or referrer policy in their controller responses; deployment headers are unverified.

Fix: enable/configure MathJax's `ui/safe` extension to restrict generated URLs/styles, and validate the final SVG with a namespace-aware presentation allowlist outside the rendering engine. Preserve required glyph paths, fragment references and the supported outer foreignObject while rejecting dangerous URI schemes and active document features. Apply the output policy to both renderer paths and version their cache keys when it changes. Serve SVG with restrictive CSP, `X-Content-Type-Options: nosniff` and referrer policy, preferably from a dedicated origin without application cookies. Header protections do not accompany downloaded/inline SVG everywhere. [MathJax safe-extension documentation](https://docs.mathjax.org/en/v3.2/options/safe.html).

### 9. Cross-request data leakage — substantially mitigated; isolation validation remains

Fresh Chromium contexts reduce cookie/storage/service-worker leakage. Browser routing admits only the current document URL and trusted assets. The loopback asset server uses random document identifiers and removes documents after rendering.

The asset server is nevertheless shared by the renderer process and is not authenticated against other local processes. The formula renderer also reuses a synchronized GraalJS context. No cross-request leak was demonstrated in this review. Keep isolation tests and give a separate worker access only to its own current document/assets; test reused formula state before relying on it for sensitive input.

### 10. Browser permissions and interactive features — partially mitigated

No permissions are granted by the context setup; frames, forms, popups, navigation and downloads are restricted. These remove common interaction paths. There is no explicit comprehensive interactive-feature policy or Permissions-Policy header.

Retain deny-by-default permissions, add a restrictive Permissions Policy where relevant, and verify dialogs/interactive operations cannot stall the worker. Prioritize worker termination and isolation over assuming every browser API can be disabled with JavaScript.

### 11. Decompression and JSON denial of service — open, high priority

`StringCompressionService.decompress` decodes the whole Base64 value and uses unbounded `GZIPInputStream.readAllBytes()`. `/image` and `/decode` decompress before descriptor validation. A small compressed query can therefore cause large allocations despite HTTP query-size limits. `/decode` does not invoke the rendering service's descriptor validation.

Bound encoded input, compressed bytes and decompressed bytes while reading; then apply service-specific JSON depth/string/list and descriptor limits consistently to both paths. Jackson has default parser constraints, but those do not bound the preceding GZIP expansion. Reject over-limit input before cache-key serialization or rendering. Also bound `/url` and `/img` input sizes.

### 12. Expensive layout, math and parsing — partially mitigated, high-priority gaps

Image width is validated from 220 to 4000, remote stylesheet downloads have limits, and submitted images/fonts/resource URLs/animations outside the CSS subset are removed. These are real improvements.

There are no explicit service limits on HTML/formula length, DOM node count, inline CSS size, total rule/selector complexity, rendered height or output bytes. Allowed CSS can still create expensive layouts and extreme dimensions. CSS nesting checks occur after parsing and are not a parser resource boundary. MathJax-generated DOM can also grow substantially. Export copies the DOM and computed styles and holds several representations in memory.

Add limits before parsing/rendering, enforce height/output limits, and bound total work with worker CPU/memory limits. Reject oversized output before returning/caching it; checking cache weight after generation is too late.

### 13. Deadlines, queues and work cancellation — open, high priority

`ImageRendererService` uses `newSingleThreadExecutor`, whose queue is unbounded. HTML sanitization, external fetching and CSS parsing happen on the calling thread before submission, outside the render future timeout. Render waiting has a deadline and calls `task.cancel(true)`, but no supervisor kills/replaces a hung Chromium worker. A native stall can monopolize the single renderer and queue subsequent work.

The stylesheet timer cancels HTTP requests and closes connections, but blocking system DNS and CSS parsing are not demonstrated to stop on that deadline. Formula rendering has no deadline (15).

Use bounded admission/queues and fetch concurrency, reject overload, enforce an end-to-end budget including preparation, and have an independent supervisor forcibly terminate and recreate unhealthy workers. Java future cancellation alone is not that guarantee.

### 14. Cache abuse and persistence — partially mitigated

Caffeine has a configurable weight budget (1 GB by default), estimates string/key memory, shares generation for identical keys and does not cache failed generations. HTML keys include `presentation-policy-3`; formula keys have their own format/MathJax version. Expiry is 60 days **after access**, so repeated hits can extend residency.

Unique cache misses still cause unbounded aggregate generation/preparation pressure, and cache weight does not cap transient or individual output allocation. Unsafe SVG can be retained. Add miss rate/admission limits and a per-artifact size limit, sanitize before storage, and change policy versions after output-security fixes. External stylesheet contents can change without a URL/key change; define refresh/TTL semantics if freshness matters. Current storage is in-memory; future persistence would require purge/quarantine support.

### 15. Formula-only renderer abuse — open availability risk; host access restricted

`FormulaSvgRenderer` passes formula text as a value rather than executable JavaScript and disables Java host access/class lookup. Those protections address direct host/script injection. `/img` uses neither Chromium nor external CSS fetching.

Rendering is synchronized in one reused GraalJS context, with no formula size/work deadline, process-level memory/CPU boundary or final SVG validator. An expensive input can block other formula requests and consume API-process resources. Add input/output limits, supervised execution/isolation and the common output policy from 8. The MathJax TeX-link probe targeted the HTML renderer; it is not evidence of that exact payload working in ASCIIMath-only `/img`.

### 16. Data exposure and supply chain — partially mitigated; ongoing operational work

Endpoint logging records the request path, handler, status/error type and duration rather than query payloads. Cache logging uses hashed identities. Nevertheless, compressed permalinks and plain formula queries expose content to browser history, reverse-proxy/access logs and monitoring unless those systems redact them. Compression provides no confidentiality.

Dependencies have declared versions, and browser installation uses the selected Playwright version. This reassessment did not perform a complete CVE scan or establish that dependencies are vulnerability-free. Establish automated dependency/browser advisory checks, reproducible artifact verification and timely updates. Use an immutable runtime image with browser binaries unwritable by the runtime account. Add referrer controls and document permalink privacy. If persistence is added, use server-generated identifiers, quotas, atomic writes and safe database/XML handling.

## Recommended next work

1. Fix confirmed generated-link handling and validate SVG before caching/delivery (8); update security cache versions.
2. Bound decompression and all entry-point payloads, including `/decode` (11).
3. Bound preparation/fetch/render admission, DOM/CSS/math complexity, output and height; supervise deadlines for both rendering paths (12–13, 15).
4. Deploy a sandboxed unprivileged rendering worker with resource limits and OS egress restrictions (3–6). Keep the controlled CSS fetcher available outside its restricted network boundary.
5. Finish delivery headers/origin isolation, cache-miss controls and operational patch/privacy policies (8, 14, 16).

The target architecture remains an API, a controlled stylesheet fetcher and a restricted rendering worker. It preserves external styling while putting limits and independent validation around untrusted content.
