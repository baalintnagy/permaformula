# Image-generation validation inventory

Updated: 17 October 2026. This describes the working tree, not live deployment.
Input means caller-supplied source/request data. Intermediate means prepared documents,
compiled math, measured layout or internal messages. Output includes generated and cached
images and API responses.

## Ownership and shared mechanisms

- The Java API owns rendering-request validation. `/exp-svg` and `/exp-jpg` accept only
  `formula`. HTML endpoints retain `ubcq`; library formula links recover the original formula
  from their stored descriptor.
- Dedicated Jakarta Bean Validation constraints `ValidFormula`, `ValidDescriptor`, `ValidCompose`,
  `ValidEncoded` and `ValidJson` replace the category-dispatch aspect. Their matching
  `ConstraintValidator` implementations own the validation rules. `ValidCompose` validates
  method parameters together; `ValidDescriptor(presentation = false)` validates decoded
  return values without requiring inert stored source to be renderable.
- Spring's standard method-validation proxies apply these constraints to `@Validated` API
  beans. Self-invocation bypasses proxies, so public entry points carry their own constraints.
  Validators run under preparation admission and request deadlines through
  [ConstraintChecks](../permaformula-api/src/main/java/org/permaformula/validation/ConstraintChecks.java).
  Standalone provider construction supports validation outside Spring without API execution control.
  [ValidationExecutionExceptionHandler](../permaformula-api/src/main/java/org/permaformula/validation/ValidationExecutionExceptionHandler.java)
  preserves operational 503/504 failures wrapped by the validation provider and delegates ordinary
  constraint failures and validator bugs to the existing local exception handler.
- [QuickValidator](../render-contract/src/main/java/org/permaformula/validation/QuickValidator.java)
  evaluates predicates and obtains the supplied exception only on failure. `notNull` and
  `orElse` replace the previous `Objects.requireNonNull` variants. Input failures use
  `ConstraintViolationException`; the existing `local-exception-handler` returns HTTP 400 with
  structured violations and correlation metadata. Internal state/execution failures retain
  their existing categories.
- API rendering rejects unsupported submitted HTML/inline CSS instead of silently removing content. Parsing,
  serialization, controlled import expansion and text-boundary escaping remain document
  preparation operations.
- Compiled-math and measured-layout checks remain in JavaScript because MathJax/Chrome already
  produce those structures. Recreating them in Java would duplicate parsing/typesetting/layout.

## Input validation

| Code | Layer | Responsibility |
| --- | --- | --- |
| [PermaFormulaController](../permaformula-api/src/main/java/org/permaformula/PermaFormulaController.java) | Java API | Required parameters, Jakarta constraints and declarative input contracts. Simple-expression endpoints have no UBCQ parameter. |
| [InputLimits](../permaformula-api/src/main/java/org/permaformula/validation/InputLimits.java) | Java API, via constraints | Nonblank source; UTF-8 HTML/formula/CSS/reference limits; width 220–4000; at most eight nonblank references. Byte counting is also reused for output/fetch accounting. |
| [PresentationValidation](../permaformula-api/src/main/java/org/permaformula/rendering/PresentationValidation.java) | Java API, via constraints | HTML/CSS presentation policy and stylesheet destination syntax before image service/cache/renderer work. |
| [ImageDescriptor](../permaformula-api/src/main/java/org/permaformula/url/ImageDescriptor.java) | Java API model | Non-null source invariant, immutable reference list and Jakarta constraint metadata. Request-wide size/presentation policy is applied by advice. |
| [UrlService](../permaformula-api/src/main/java/org/permaformula/url/UrlService.java) | Java API | JSON types, nesting/token/name/string/number limits and trailing-token rejection. Advice checks decoded descriptor bounds. Codec endpoints preserve original source as inert data; presentation rules apply when rendering. |
| [StringCompressionService](../permaformula-api/src/main/java/org/permaformula/compression/StringCompressionService.java) | Java API | Declarative encoded/uncompressed bounds; Base64/GZIP parsing; compressed and streamed decompressed byte limits. Incremental limits stay next to allocation/decompression. |
| [RenderContentPolicy](../permaformula-api/src/main/java/org/permaformula/rendering/RenderContentPolicy.java) | Java API | Rejects HTML parse errors, unsupported elements/attributes and invalid inline CSS. Accepted source is used without security filtering. |
| [StylesheetPolicy](../permaformula-api/src/main/java/org/permaformula/rendering/StylesheetPolicy.java) | Java API | Strict input CSS parsing/interpretation; permitted rules/properties/functions; selector length, nesting, grid repetition; rejects resource declarations and unexpanded imports/namespaces. Output-CSS mode is separate. |
| [PreparationComplexity](../permaformula-api/src/main/java/org/permaformula/rendering/PreparationComplexity.java) | Java API | HTML elements/nodes/depth/attributes/inline-style length; CSS tokens/token length/selectors/rules/declarations/nesting. Parser-local guards run before recursive parsing or excessive retention. |
| [StylesheetFetcher](../permaformula-api/src/main/java/org/permaformula/rendering/StylesheetFetcher.java) | Java API | HTTPS/port/credentials/fragments/public addresses, redirects, status/MIME/encoding/response size, import cycles/depth/resource count and aggregate bytes. Direct DNS is checked at connection time; proxied deployments also enforce destinations at the egress proxy. |
| [api.ts](../frontend/src/api.ts), [App.tsx](../frontend/src/App.tsx) | Browser UI | UBCQ syntax/extraction and editor stylesheet-reference checks. |
| [preview-document.ts](../frontend/src/preview-document.ts) | Browser preview | Existing DOMPurify preview sanitization, MathJax safe options, stylesheet-reference filtering and zoom-message checks. Local previews are isolated and do not establish API acceptance. API rendering receives the original source and rejects invalid content. |

`renderer-core` has no duplicate request schema, width, nonblank or formula-length validator.
Native parser errors are still reported as failed rendering operations.

## Intermediate validation

| Code | Layer | Responsibility |
| --- | --- | --- |
| [RenderContentPolicy](../permaformula-api/src/main/java/org/permaformula/rendering/RenderContentPolicy.java) | Java API | Prepared-DOM bounds, combined CSS-rule count and CSS-rule × HTML-element work limit; safe template/style construction and stylesheet order. |
| [GrpcRenderGateway](../permaformula-api/src/main/java/org/permaformula/rendering/GrpcRenderGateway.java) | Java API | Prepared payload byte limit; translates renderer input failures into API constraint violations. |
| [WorkerMessages](../renderer-core/src/main/java/org/permaformula/rendering/WorkerMessages.java) | Java transport | Message lengths before allocation/write, protobuf parsing and truncation detection. These are operational transport/resource checks. |
| [RenderWorkerMain](../renderer-core/src/main/java/org/permaformula/rendering/RenderWorkerMain.java) | Java worker | Internal worker-kind invariant; content validation belongs to the API. |
| [mathjax-safety.js](../tools/svg/mathjax-safety.js) | JS renderer | Discovered HTML expression count/length, compiled MathML node/depth/aggregate limits, error nodes, malformed compiled URLs/font attributes and MathJax attribute policy. The wrapper rejects changes to attribute meaning, including TeX post-filters; native style/number/class normalization is compared semantically and accepted original attributes are preserved. Plain formula source checks are disabled here and owned by the API. |
| [browser-entry.js](../tools/svg/browser-entry.js), [complexity-limits.js](../tools/svg/complexity-limits.js) | JS renderer | DOM complexity before/after typesetting, remaining MathJax errors and native TeX macro/buffer limits. Plain formula length is checked in Java; discovery checks protect expressions inside HTML. |
| [browser-assets.js](../tools/svg/browser-assets.js) | JS renderer | Failed image-load detection while waiting for assets. |
| [export-styled-svg.js](../tools/svg/export-styled-svg.js) | JS renderer | Measured document dimensions/area, element positions/dimensions and DOM complexity before export. |
| [ChromiumRenderer](../renderer-core/src/main/java/org/permaformula/rendering/ChromiumRenderer.java), embedded JS in `jpeg()` | Chrome | Intermediate SVG dimension syntax, raster dimensions/scale and successful image decoding. |

## Output validation

| Code | Layer | Responsibility |
| --- | --- | --- |
| [render-formula.js](../tools/svg/render-formula.js) | JS renderer | Generated formula SVG tree size/depth, dimensions/area and serialized length. |
| [export-styled-svg.js](../tools/svg/export-styled-svg.js) | JS renderer | Export data-URL format, XML parse success, SVG root/namespace and size; output normalization removes script/link/style nodes and obsolete `externalResourcesRequired`. |
| [ChromiumRenderer](../renderer-core/src/main/java/org/permaformula/rendering/ChromiumRenderer.java), embedded JS | Chrome | Expected JPEG canvas data-URL prefix. |
| [RenderWorkerMain](../renderer-core/src/main/java/org/permaformula/rendering/RenderWorkerMain.java), [RenderJobMain](../renderer-core/src/main/java/org/permaformula/rendering/RenderJobMain.java) | Java worker | Nonempty output and bounded bytes, including JPEG. |
| [RenderWorkerPool](../renderer-core/src/main/java/org/permaformula/rendering/RenderWorkerPool.java), [SupervisedRenderer](../renderer-core/src/main/java/org/permaformula/rendering/SupervisedRenderer.java), [GrpcRenderGateway](../permaformula-api/src/main/java/org/permaformula/rendering/GrpcRenderGateway.java) | Java transport | Response framing/status, request ID, nonempty content and bounded response bytes. |
| [SvgOutputPolicy](../permaformula-api/src/main/java/org/permaformula/rendering/SvgOutputPolicy.java) | Java API | XML structure/namespaces/elements/attributes, foreignObject placement, URLs, dimensions, tree/attribute/namespace counts, bytes, DTD/entity/processing-instruction rejection and CSS sanitization. Output rejection retains its existing failure classification. |
| [StylesheetPolicy.outputDeclarations](../permaformula-api/src/main/java/org/permaformula/rendering/StylesheetPolicy.java) | Java API | Generated inline CSS structure/filtering and local SVG references. This remains output policy rather than input stripping. |
| [JpegOutputPolicy](../permaformula-api/src/main/java/org/permaformula/rendering/JpegOutputPolicy.java) | Java API | JPEG size, markers, readable metadata, dimensions/area and cached encoding/prefix; no full pixel-buffer decode. |
| [JdbcImageCache](../permaformula-api/src/main/java/org/permaformula/rendering/cache/JdbcImageCache.java) | Java storage | Revalidates persisted SVG/JPEG and discards invalid entries. Policy versions participate in content/token identities. |
| [api.ts](../frontend/src/api.ts), [App.tsx](../frontend/src/App.tsx) | Browser UI | Returned descriptor types/width, UBCQ syntax, SVG MIME type and finite positive dimensions. |
| [libraryPreview.ts](../frontend/src/libraryPreview.ts) | Browser UI | Bounded local GZIP decoding and source-type checking for stored formula descriptors. Formula links use `formula`; HTML links use `ubcq`. Public/admin views share the implementation; link preparation requires no additional source API request. |

## Execution controls

- Java API: deadlines, bounded preparation/generation/fetch work and client admission.
  [BoundedWork](../permaformula-api/src/main/java/org/permaformula/rendering/BoundedWork.java) retains a
  slot until its worker exits, including after cancellation. Same-worker nested validation
  neither acquires another slot nor restarts the deadline.
- Java renderer supervision: bounded queues/payload accounting, gRPC cancellation/deadlines,
  worker processes, termination and replacement.
- Java/Playwright: browser timeouts, Chromium sandbox setting, request routing,
  navigation/frame/popup restrictions and download/service-worker blocking.
- [browser-guards.js](../tools/svg/browser-guards.js): disables worker/WebRTC/WebTransport/
  WebSocketStream/popup/service-worker APIs before document scripts execute.
- Document/response CSP and sandbox restrictions; deployment process/network isolation.

Hard deadlines and process termination are implemented in Java, not JavaScript.

## Shared policy and generated copies

[ComplexityPolicy](../render-contract/src/main/java/org/permaformula/contract/ComplexityPolicy.java)
loads validated limits and supplies them to Java and JavaScript.
[RenderProtocol](../render-contract/src/main/java/org/permaformula/contract/RenderProtocol.java)
defines transport bounds. JavaScript under `tools/svg` is bundled into renderer resources;
generated copies are not independent policies. Lombok-generated dependency constructors add
no implicit input validation.

## External stylesheet compatibility

Downloaded CSS libraries use `StylesheetPolicy.externalStylesheet`, rather than the inline
property allowlist. Bootstrap 4.4.1 is covered by a pinned fixture and Chromium render test.
The server validates fetch destinations, expands imports within the existing limits and
escapes style-element boundaries. It retains vendor properties, data URLs and animation rules, while preserving selector-length,
function-depth and grid-expansion limits.
Chrome's CSP and network isolation continue to deny dependent resource requests, including
remote fonts and images; accepting their declarations does not fetch those resources.
Submitted inline CSS retains its stricter presentation policy. HTML cache identity is v12.
