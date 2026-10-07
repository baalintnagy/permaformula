# Third-party library replacement review

Reviewed 2026-10-07. This is an implementation review, not a dependency vulnerability audit or a completed migration. Production code and dependencies were not changed; no new runtime tests were run. Library capabilities were checked against upstream documentation. Adoption recommendations below are engineering judgments, and candidates requiring a prototype are identified explicitly.

## Scope and conclusion

Reviewed the four Maven modules, their 52 handwritten production Java files (about 3,475 lines), the authored JavaScript under `tools/svg`, dependency declarations, key behavioral/security tests, the existing AntiSamy trial, and deployment/smoke-test infrastructure patterns. Generated protobuf classes, generated JavaScript bundles and vendored MathJax are third-party/generated code, not replacement targets.

The project already delegates its largest general-purpose tasks to libraries: Spring MVC/Validation/Jackson, gRPC/Protobuf, Playwright, GraalJS/MathJax, html-to-image, OWASP Java HTML Sanitizer, jsoup, ph-css, Apache HttpClient, Bucket4j and Caffeine. Most remaining large classes combine those tools with application-specific security and resource policies.

The best substantive replacement candidate is Caffeine's async API for in-flight image coordination. The clearest test-only cleanup is Awaitility. Logging can be standardized if its desired output contract is made explicit. Existing Guava utilities offer smaller improvements without another production dependency.

## Candidates worth pursuing

### 1. Duplicate image generation coordination: Caffeine AsyncCache

**Location:** `permmath-api/.../rendering/cache/CaffeineImageStore.java`, especially `loading`, `getOrGenerate` (line 38) and `load` (line 76).

The class combines a synchronous Caffeine cache with a separate `ConcurrentHashMap<String, CompletableFuture<String>>`. The map publishes one pending generation, allows duplicate callers to wait with a deadline, propagates exceptions and removes completed loads. This is standard cache coordination implemented alongside a library that already provides it.

Use `AsyncCache<String, String>` and its atomic `get(key, mappingFunction)` API. Caffeine provides a shared future per key and automatically removes failed asynchronous computations. Weighted retention, access expiry and statistics can remain Caffeine responsibilities. This is the strongest opportunity to remove a whole mechanism, with no new dependency.

**Conditions:** keep bounded generation admission; capture the initiating client's rate-limit context and original request deadline before changing threads; charge generation tokens only inside the actual mapping function; unwrap checked exceptions; give every waiter its own bounded wait. Do not call `cancel`, `orTimeout` or `completeOnTimeout` on the shared future when only a duplicate caller expires. Do not move work onto an unconstrained default executor. Check oversized-result eviction, TTL reset, statistics and failure retry behavior against the existing tests. The future publication and actual worker execution need deliberately designed ordering; a default async loader is not a drop-in change.

**Verification:** `CaffeineImageStoreTests`, `GeneratedImageServiceTests`, `ClientRateLimitsTests.sharedInFlightCacheLoadDoesNotSpendAnotherClientsGenerationTokens`, and API deadline/admission tests.

[Caffeine population documentation](https://github.com/ben-manes/caffeine/wiki/Population) and [AsyncCache contract](https://github.com/ben-manes/caffeine/blob/master/caffeine/src/main/java/com/github/benmanes/caffeine/cache/AsyncCache.java).

### 2. Test polling: Awaitility

**Location:** `renderer-core/.../RenderWorkQueueTests.java:35`, `SupervisedRendererTests.java`, and repeated condition/sleep loops.

Replace `eventually(BooleanSupplier)` and loops waiting for PID publication, process death or admission recovery with bounded Awaitility conditions. It supplies timeout handling and useful failure diagnostics. This is a clear test-only replacement; check whether the resolved test dependencies already contain it before adding a declaration.

Keep latches for deliberately ordering concurrent operations. A sleep that deliberately exercises an elapsed-time boundary is different from polling for eventual state. Set explicit bounds/poll intervals for the short queue tests, and preserve the original thread where interruption/thread-local behavior is being tested.

[Awaitility usage](https://github.com/awaitility/awaitility/wiki/Usage).

### 3. Request logging: Logbook, or Spring/Micrometer observations

**Location:** `permmath-api/.../logging/EndpointLoggingAspect.java:19`; `EndpointLoggingTests`.

The aspect manually times controller invocations and logs method, path, handler, status or exception type. It cannot observe rejections occurring before controller invocation, and on failure the final HTTP status is determined later.

If the requirement is structured HTTP access logs, evaluate Zalando Logbook. If it is request duration/status metrics and traces, use Spring Boot Actuator/Micrometer's existing MVC instrumentation. These solve different requirements: Micrometer is not an automatic replacement for the current textual log lines.

Configure Logbook to omit bodies and redact all content-bearing query parameters, including `formula`, `ubcq`, `style`, `styleRefs` and `text`. Avoid buffering multi-megabyte SVG responses for logging; a maximum printed body size alone does not prevent full buffering. Preserve no-payload logging regressions. The exact `handler` and event fields may require a small adapter. Verify the chosen integration against Boot 4.0.5 rather than assuming any starter version is compatible. The AspectJ starter may become removable if no other aspect needs it.

**Verdict:** worthwhile standardization if broader HTTP visibility is wanted; borderline if preserving the exact present log contract is the only goal.

[Logbook configuration and buffering behavior](https://github.com/zalando/logbook), [Spring Boot metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html).

### 4. UTF-8 size checks: existing Guava Utf8.encodedLength

**Location:** `permmath-api/.../validation/InputLimits.java:text`, and byte-count-only conversions in SVG/fetched-CSS validation.

`value.getBytes(UTF_8).length` allocates the entire encoded byte array solely to measure it. Guava is already a direct dependency; `Utf8.encodedLength(value)` avoids that allocation. Keep the inexpensive character-count rejection before byte counting. This is mostly an allocation improvement, not a large code deletion.

**Borderline behavior:** Guava rejects unpaired UTF-16 surrogates; the current JDK encoding replaces malformed input. Decide whether rejection is intended, and cover this explicitly. Do not change sites that actually need encoded bytes for transport, compression or hashing.

[Guava Utf8 API](https://guava.dev/releases/33.4.8-jre/api/docs/com/google/common/base/Utf8.html).

### 5. SHA-256 formatting: existing Guava Hashing

**Location:** `GeneratedImageService.java:key` (line 77).

`Hashing.sha256().hashString(identity, UTF_8).toString()` can replace the `MessageDigest`, `HexFormat` and impossible-algorithm exception boilerplate. Guava is already available in this module. Preserve the exact identity input and lowercase SHA-256 output: cache keys must remain identical.

**Verdict:** reasonable small cleanup, not significant reinvention. Keep the JDK implementation in `render-contract/ComplexityPolicy.java:fingerprint` unless Guava is independently needed there; adding a dependency to that module for a few lines is poor value.

[Guava Hashing API](https://guava.dev/releases/33.5.0-jre/api/docs/com/google/common/hash/Hashing.html).

## Borderline candidates and rejected substitutions

| Area and location | Candidate | What could disappear | Why replacement is conditional / recommendation |
|---|---|---|---|
| `BoundedWork`, `ApiWorkService` | Resilience4j semaphore bulkhead + TimeLimiter | Semaphore/admission and some timeout plumbing | Zero-wait admission is supported, but preserve permit retention until actual worker exit, including uninterruptible work, nested deadlines, checked exceptions and 503/504 mapping. Wrap the actual operation with the bulkhead and treat timeout separately. A naive future-completion decorator can release capacity too early. Prototype only if a wider resilience/metrics requirement exists. |
| `RenderWorkQueue` | Resilience4j thread-pool bulkhead | Fixed worker/count-bounded queue mechanics | Existing JDK executor already supplies these. Resilience4j does not by itself express the retained-byte budget, separate queue-wait deadline, physical removal of cancelled queued payloads or distinct queued/running failure semantics. Most custom code would survive. Keep current implementation. |
| `WorkDeadline`, rate-limit context handoff | Existing gRPC `Context`/`Deadline`, or Micrometer Context Propagation | Manual thread-local capture/restore across execution boundaries | gRPC already provides deadlines and wrapped tasks; Micrometer provides context snapshots. Neither defines HTTP status mapping, worker interruption or client lease lifetime. gRPC reuse adds no library but couples generic API work to transport abstractions. Micrometer is more compelling if tracing/MDC propagation is also introduced. |
| `StylesheetFetcher.publicAddress` (line 273) | IPAddress CIDR/range containment | Hardcoded byte arithmetic for IPv4/IPv6 ranges | A declarative denylist is easier to inspect. The library implements address/subnet operations, not this application's definition of permitted public egress. Preserve special-purpose/transition exclusions, mapped-address behavior, reject mixed DNS answers, and connect to the validated address snapshot. Cross-check the Java policy and Squid list. Optional; no established security improvement from dependency adoption alone. |
| `StylesheetFetcher.download` (line 136) | More Apache HttpClient-native redirect/deadline handling | Some of the manual redirect/client lifecycle loop | HttpClient is already used. Preserve destination validation on every redirect, the total deadline including response bodies, no cookies/retries/decompression, and immediate closure before entity draining. A conventional HTTP wrapper or automatic redirect handler does not meet those conditions automatically. Keep unless a tested refactor clearly reduces complexity. |
| `StylesheetFetcher.expand` (line 91) | CSS import/inlining library | Recursive import expansion | Must preserve final redirect base URI, stylesheet/media order, cycle/depth/resource/combined-byte limits and controlled fetch policy. It already uses ph-css for parsing and writing. No drop-in replacement established; retaining this small orchestration layer is reasonable. |
| `SupervisedRenderer.run` (line 53) | Apache Commons Exec | Process startup, stream pumps, timeout watchdog | Commons Exec supplies process execution and a watchdog, but bounded protobuf pipes, request correlation, interruption, forced descendant termination and renderer restart behavior remain custom. Its watchdog source calls `process.destroy()`; that is not equivalent to this implementation's forced process/descendant cleanup. Keep JDK process handling. |
| `GrpcRenderGateway`, `RendererServiceMain` | Spring gRPC | Channel/server setup, configuration and lifecycle | Promising on the Spring API side. Unix-domain epoll, mutual TLS, message limits and event-loop ownership need a prototype against the selected release. The renderer is deliberately a small standalone service; adding Spring Boot there solely to shrink startup code is a larger tradeoff. Keep request construction, response validation and HTTP error mapping. |
| `StylesheetPolicy` | AntiSamy or OWASP's standard CSS sanitizer | Property/function/rule filtering | The repository's AntiSamy 1.7.8 trial reports lost variables/calc/media/supports/layers/nesting and corrupted grid/gradient output even with a diagnostic property policy. Existing modern CSS behavior would regress. OWASP's schema is already reused. Keep ph-css plus the application policy under the current contract. |
| `SvgOutputPolicy` | DOMPurify | SVG/HTML/MathML allowlist traversal | DOMPurify supports these markup families, but requires a DOM runtime and policy customization. Renderer-side sanitization would not replace independent API validation. Default policy must be checked for required foreignObject, custom MathJax nodes, inline CSS and fragments. XML namespace/DTD checks, structure/dimension/byte budgets and fail-closed behavior would remain. No straightforward Java/API replacement established. |
| `SvgOutputPolicy.dimension` | Batik SVG/CSS unit parsing | Small ex/em/px parser | The accepted unit set is intentionally narrow and uses configured conversion factors. A broader SVG parser adds substantial machinery and changes accepted syntax. Keep the small policy parser unless the output contract expands. |
| `StringCompressionService.decompress` | Commons IO BoundedInputStream / Commons Compress | Manual bounded copy loop | Compression and Base64 already use JDK libraries. A bounded stream can stop at its limit without proving the original payload ended; read one extra decompressed byte and reject overflow. Preserve concatenated GZIP members and truncated/corrupt stream tests. The JDK `readNBytes(max + 1)` could simplify this more cheaply than another library. |
| `PermMathProperties.origin`, `StylesheetFetchProperties` | Jakarta `@URL`, URI builders or generic URL validators | Some URI field checks | This contract permits origins/particular proxy forms, not arbitrary valid URLs. Credentials, path, query, fragment, scheme, ports and same-origin defaults remain custom. The JDK URI parser already supplies syntax handling. Keep application checks. |
| `ValidImageWidth`, `InputLimits`, property constructors | More Jakarta Bean Validation annotations | Selected scalar min/max/not-blank checks | `ValidImageWidth` already composes `@Min`/`@Max`. UTF-8 byte limits, full-refill expiry checks and validation of direct constructor/decoder callers need custom enforcement or explicit validator invocation. Moving everything to annotations would not automatically preserve those entry points. Small optional deduplication only. |
| `ComplexityPolicy` configuration and `json()` | OWNER/Typesafe Config or Jackson | Environment/property lookup and small JSON emitter | Module independence and worker-environment propagation matter. JSON contains only fixed enum names and validated integers, not user text. General configuration/serialization dependencies would likely outweigh the deletion. Keep until configuration needs materially expand. |
| HTTP fixtures in `BrowserChannelPolicyTests`, `BrowserRenderingTests` | WireMock | Handwritten response routing/counters | Good if fixtures grow: stubs, request verification, delays and fault injection are standard library features. Tiny loopback tripwire servers are currently simple and useful. Keep native raw socket/slow-ingress probes: a normal HTTP mock changes what those tests exercise. |
| `scripts/test-*.ps1` Docker fixture lifecycle | Testcontainers | Container/network startup, readiness and cleanup | Worth considering for cross-platform automated integration tests. Preserve exact isolated/multiple-network topology, static trusted proxy identity, security options, DNS fixtures and raw-socket probes. Generic container defaults/ambassador networking can alter the tested boundary. Deployment smoke scripts are still useful independently of JUnit. |
| `RenderAssets` | Jetty/Undertow or framework static serving | Small loopback asset HTTP handler | JDK HttpServer already supplies HTTP serving. A new server dependency buys little for a disposable worker with a narrow trusted asset set. Keep. |

Supporting upstream documentation: [Resilience4j bulkheads](https://resilience4j.readme.io/docs/bulkhead), [TimeLimiter](https://resilience4j.readme.io/docs/timeout), [gRPC Context](https://grpc.github.io/grpc-java/javadoc/io/grpc/Context.html), [Micrometer context propagation](https://docs.micrometer.io/context-propagation/reference/purpose.html), [IPAddress](https://github.com/seancfoley/IPAddress), [Commons Exec watchdog source](https://commons.apache.org/exec/xref/org/apache/commons/exec/ExecuteWatchdog.html), [Spring gRPC client](https://docs.spring.io/spring-grpc/reference/client.html), [DOMPurify](https://github.com/cure53/DOMPurify), [Commons IO bounded streams](https://commons.apache.org/proper/commons-io/apidocs/org/apache/commons/io/input/BoundedInputStream.html), [WireMock faults](https://wiremock.org/docs/simulating-faults/), [Testcontainers networking](https://java.testcontainers.org/features/networking/) and [readiness](https://java.testcontainers.org/features/startup_and_waits/).

The AntiSamy compatibility conclusions above are from the checked-in local evaluation in `tools/antisamy-trial/README.md`, not a trial rerun during this review.

## Existing delegation that should remain

- `RenderContentPolicy` already uses OWASP HTML Sanitizer and jsoup; its remaining code describes presentation policy, CSP and complexity checks.
- `StylesheetPolicy` and `PreparationComplexity` already use the ph-css AST/tokenizer; there is no independent application CSS lexer to replace. The preparse complexity budget protects work before recursive parsing.
- `ClientRateLimits` already uses Bucket4j, Caffeine and Guava IP parsing. Active leases, bounded client admission and strict trusted-proxy behavior are application rules; a stock rate-limit filter would not necessarily preserve them.
- `export-styled-svg.js` already delegates cloning/styles/foreignObject serialization to html-to-image. Measurement, resource filtering and output normalization are the project's contract.
- `mathjax-safety.js` uses MathJax render actions, compiled trees and safe filtering; the ASCIIMath-specific hook is required by the current integration. Replacing it with only the usual TeX safe configuration is not equivalent.
- `BrowserChannelPolicy` already uses Playwright interception and context APIs. Worker/WebRTC/popup/frame guards and independent network/container boundaries still need explicit policy.
- Formula rendering, protobuf framing, JSON decoding constraints, GZIP/Base64 and query encoding already use libraries or standard JDK implementations.
- `ImageDeliveryBoundary`, SVG response headers and rendering/cache identity versions encode product/security requirements. Generic web/security frameworks can host these policies but do not define them.

## Suggested adoption order

1. Prototype Caffeine AsyncCache behind `GeneratedImageStore` and run the cache, quota and deadline regressions together.
2. Replace repeated eventual-state polling with Awaitility in renderer tests.
3. Use Guava byte counting where allocation is unnecessary, after deciding malformed UTF-16 behavior; optionally shorten API hashing with existing Guava.
4. Standardize HTTP logging/observations if the desired log format and coverage justify it.
5. Consider Spring gRPC, IPAddress and Testcontainers only when their wider configuration, policy-maintenance or integration-test benefits justify the extra dependencies.

Do not adopt AntiSamy for the current CSS contract or replace bounded cancellation/process isolation with generic timeout wrappers without preserving the documented behavior.
