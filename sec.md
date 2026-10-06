The current /image renderer should be treated as executing untrusted web content on your server. The main protections needed are input sanitization, network restrictions, process isolation, resource limits, and safe SVG delivery. Each protects a different boundary.
I reviewed the implementation; this is a threat assessment, not a penetration test. I haven’t changed the code.
The most significant findings are:
- Submitted HTML and CSS enter the document without sanitization.
- Scripts and event handlers are removed during export, after they could already execute.
- Browser engines can request external resources without a service-level network policy.
- Chromium’s launch doesn’t enable its sandbox; Playwright documents that option as disabled by default. Our Dockerfile also doesn’t configure a non-root runtime user. Playwright launch options, Playwright Docker guidance
- Decompression, render queues, resource downloads, and generated output have insufficient size limits.
These are the attack paths I would address.
1. JavaScript execution through submitted HTML
   Vector: <script>, event attributes such as onerror, nested documents, SVG scripting, or injected markup execute inside the renderer. They can alter the document, make requests, consume resources, or forge the result.
   Mitigation: Sanitize HTML before the browser parses it, using an allowlist of presentation elements and attributes. Remove scripting, event attributes, embedded documents, forms, and navigation features. Keep JavaScript enabled only for our trusted MathJax and exporter code, with a restrictive CSP.
   Use a maintained parser-based sanitizer such as OWASP Java HTML Sanitizer, rather than regex replacements. It needs an explicit policy suited to our supported HTML and CSS.
2. Breaking out of the stylesheet
   Vector: The style field is inserted directly into a <style> element. Content containing an HTML closing tag can escape that element and introduce markup. CSS itself can also import external resources.
   Mitigation: Parse and sanitize CSS separately. Never concatenate raw CSS into HTML. Build the document using APIs that preserve text boundaries, and apply a restricted CSS property/value policy. Treat external stylesheets as untrusted too.
3. SSRF and attacks against internal services
   Vector: Images, stylesheets, fonts, frames, scripts, CSS url()/@import, navigation, and JavaScript requests can target localhost, private networks, cloud metadata, databases’ HTTP interfaces, or the application itself.
   Impact: Internal scanning, blind requests that change state, and potentially reading internal responses.
   Mitigation: Deny browser network access by default. If remote resources are required, fetch them through a controlled resource service that permits approved HTTPS destinations and returns bounded, validated content.
   Block private, loopback, link-local, reserved, and inappropriate IPv6 destinations. Validate redirects and bind connections to validated addresses to prevent DNS rebinding. Enforce the restriction at the network layer as well. CORS does not stop requests from being sent. OWASP SSRF guidance
4. Bypassing restrictions through alternative browser channels
   Vector: Restricting ordinary HTTP requests can leave WebSockets, service workers, popups, redirects, frames, WebRTC, DNS traffic, or other mechanisms available.
   Mitigation: Block workers, service workers, frames, popups, and unnecessary connection mechanisms. Apply restrictions to the whole browser context, not just the initial page. Use an OS-level egress policy as the final boundary.
   Playwright specifically warns that service-worker requests can bypass its ordinary request interception. Playwright context routing
5. Local files and operating-system integration
   Vector: Attempts to use file:, platform-specific schemes, external protocol handlers, downloads, or browser vulnerabilities to reach local files or invoke applications.
   Normal browser JavaScript does not automatically grant filesystem or shell access, but it should never run beside sensitive files with unnecessary privileges.
   Mitigation: Permit only explicitly supported URL schemes and resource types. Deny downloads and external handlers. Run the renderer with an empty temporary working directory, minimal environment variables, no secrets, no home directory mounts, and no access to project files or credentials.
6. Native browser vulnerabilities and remote code execution
   Vector: Crafted HTML, JavaScript, fonts, images, SVG, or media exploit Chromium/WebKit or their native libraries. Malicious content can reach these parsers even after scripts are removed.
   Mitigation: Patch Chromium, JavaFX/WebKit, the JDK, and dependencies regularly. Run rendering in a separate restricted process or container under an unprivileged account. Use read-only filesystems, limited temporary storage, restricted syscalls, and no host sockets or privileged mounts.
   For Chromium, explicitly enable and verify its sandbox. For JavaFX, move it into a separate worker JVM. A separate WebView inside the API JVM is not an operating-system security boundary.
7. Tampering with our rendering machinery
   Vector: Submitted scripts can replace window.exportStyledSvg, set window.renderState, provide their own window.renderSvg, or interfere with MathJax and browser APIs.
   Impact: An attacker can manufacture output rather than merely supply content for rendering.
   Mitigation: Prevent untrusted scripts from executing, isolate user content from the trusted control document, and validate the generated SVG outside the rendering page. The page’s “ready” flag should not be treated as proof that its output is trustworthy.
8. XSS in the returned SVG
   Vector: Our SVG contains HTML through foreignObject. Removing <script> and on… attributes doesn’t cover every active-content mechanism: nested frames, srcdoc, dangerous links, embedded objects, and other document features can survive.
   SVG displayed through <img> has browser restrictions, but those restrictions do not generally apply when it is opened directly or embedded as a document. MDN SVG security contexts
   Mitigation: Validate and sanitize the final SVG with a namespace-aware allowlist. Allow our outer foreignObject and approved presentation HTML, while rejecting nested active documents, dangerous URLs, and unexpected elements.
   Serve generated images from a dedicated origin without application cookies. Add restrictive CSP, X-Content-Type-Options: nosniff, and an appropriate referrer policy. CSP is an additional layer; downloaded files and inline embedding can lose response-header protections. CSP sandbox documentation
9. Cross-request data leakage
   Vector: Cookies, storage, caches, service workers, browser state, or shared execution state influence another request. Internal documents also share the asset server’s origin.
   Mitigation: Use fresh contexts and temporary profiles, no stored credentials, and no shared user storage. Chromium already uses fresh contexts, which is helpful. JavaFX needs explicit testing and isolation; creating a new WebView should not be assumed to isolate every process-wide facility.
   Workers should expose only their own trusted assets and current document—not other jobs or the API.
10. Browser permissions and interactive features
    Vector: Permission requests, dialogs, fullscreen, clipboard access, media capture, geolocation, file choosers, notifications, printing, audio, and popup creation can interrupt or abuse rendering.
    Availability varies by engine and permission model.
    Mitigation: Grant no permissions. Deny or immediately dismiss interactive operations. Block frames, forms, navigation, downloads, and popups unless a supported rendering feature specifically needs them. Add a restrictive Permissions Policy where supported.
11. Decompression and JSON denial of service
    Vector: A small ubcq expands into enormous JSON; deeply nested structures, huge strings, or large stylesheet arrays consume memory and CPU.
    Current issue: Decompression uses unbounded readAllBytes().
    Mitigation: Bound encoded input, compressed bytes, total decompressed bytes, JSON depth, string lengths, and list counts. Stop decompression when the limit is crossed. Validate decoded descriptors before caching or rendering. Apply these protections to /decode too.
12. Expensive layout, math, and resource decoding
    Vector: Huge DOM trees, extreme dimensions, pathological formulas, expensive filters, enormous image dimensions, animated content, fonts, or CSS cause excessive CPU or memory consumption.
    Mitigation: Limit DOM size, formula complexity/length, CSS size, image dimensions, resource count, total downloaded bytes, output size, and rendered height. Reject unnecessary media and animation. Use process-level CPU and memory limits.
    Download limits need to apply while streaming; a resource’s declared size cannot be trusted.
13. Timeouts that don’t actually stop work
    Vector: Infinite JavaScript or native rendering hangs continue after the HTTP request times out.
    Current issue: JavaFX’s timeout runs on the same FX thread that hostile content can block. Cancelling the Java future doesn’t guarantee that browser work stops.
    Mitigation: Have an external supervisor enforce deadlines and kill the worker process when necessary. Recreate unhealthy workers. Use bounded render queues and reject overload rather than accumulating requests.
14. Cache abuse and persistence of malicious output
    Vector: Many unique requests cause rendering load and eviction. Malicious output can remain available for 60 days. Remote resources can also change while retaining the same URL.
    Mitigation: Rate-limit cache misses, bound concurrent jobs, and limit individual artifact size before storing it. Sanitize before storage and before delivery where appropriate.
    Include a security-policy version in the cache identity. Changing sanitizer rules should invalidate previous output. We can keep the key independent of JavaFX/Chromium while still including that policy version. Future persistent storage needs purge/quarantine support as well.
15. Abuse of the formula-only renderer
    Vector: Pathological math input, MathJax bugs, GraalJS vulnerabilities, or generated SVG features can affect /img, even though it launches no browser.
    Existing protection: Formula text is passed as data, not interpolated into JavaScript, and Java host access is disabled.
    Mitigation: Keep those restrictions; add formula limits, deadlines, output validation, and isolation for expensive work. Host-access restrictions alone don’t impose CPU or memory limits. GraalVM’s stronger sandbox facilities have deployment-specific requirements we’d need to check before relying on them. GraalVM sandbox guidance
16. Leaks, supply-chain attacks, and future storage mistakes
    Vector: Permalinks expose content through access logs, browser history, referrers, and monitoring. Compression is not encryption. Dependencies and downloaded browsers introduce supply-chain risk. Future persistence introduces filesystem paths and stored untrusted content.
    Mitigation: Avoid logging payloads, redact sensitive error details, and use a restrictive referrer policy. Pin and verify dependencies/browser artifacts while maintaining an update process. Keep browser binaries unwritable by the rendering account.
    For persistence, use the server-generated hash as the storage identifier, parameterized database queries, atomic writes, size quotas, and secure XML parsing with external entities disabled if SVG is parsed server-side.
My recommended architecture is an API process, a restricted rendering worker, and a controlled resource fetcher. The worker receives sanitized content and embedded resources, has no general network access, produces SVG, and can be forcibly terminated. The API validates that output before caching and serving it.
This preserves styled HTML. It requires defining a supported subset of HTML and CSS, while excluding scripts and interactive browser features.
I would implement the protections in this order:
1. Bound decompression, inputs, queues, resources, and output.
2. Sanitize HTML/CSS before rendering and SVG after rendering.
3. Deny unrestricted network access; add controlled fetching if needed.
4. Isolate JavaFX in a worker JVM and harden Chromium’s sandbox and runtime privileges.
5. Harden SVG delivery, version the security policy in cache keys, and add hostile-input regression tests.
The highest-priority change is to stop treating arbitrary submitted HTML as trusted browser content. Output cleanup alone cannot protect the machine that already rendered it.