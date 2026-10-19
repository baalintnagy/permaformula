# AntiSamy CSS compatibility evaluation

Evaluated 2026-10-06 with AntiSamy 1.7.8 (Maven Central's current release), JDK 25
and Chromium through Playwright 1.59.0. **Do not adopt for this service's current CSS
contract.** Production dependencies and policies are unchanged.

The probe calls `CssScanner.scanStyleSheet` using the default policy, then a
diagnostic policy with permissive values for the exact properties being tested.
The second policy distinguishes configurable exclusions from deeper scanner/parser
limitations. It is deliberately unsafe and must never be used in production.
The two-argument `CssScanner` constructor disables imported stylesheet fetching.

| Feature | Default policy | Diagnostic policy |
|---|---|---|
| Basic `color:red` | Retained | Retained |
| Custom property `--accent` | Removed | Removed |
| `rgba(var(--accent),1)` | Removed | Removed |
| `calc(100% - 2px)` | Removed | Removed |
| Flex and gap | Removed | Retained |
| Grid `repeat(2,1fr)` | Removed | Corrupted to `repeat(2,,,1.0fr)` |
| Responsive `@media` | Condition removed; declarations become unconditional | Same |
| `@supports` | Rule contents removed | Same |
| Cascade `@layer` | Rule contents removed | Same |
| Nested selector | Nested rule removed | Same |
| `linear-gradient(red,blue)` | Removed | Corrupted to `linear-gradient(red,,,blue)` |
| SVG `fill:url(#glyph)` | Removed | Retained |
| Assistive MathML `clip:rect(...)` | Removed | Removed |

The Chromium check confirms the corrupted grid loses its two-column template and
the corrupted gradient becomes `background-image:none`. A `TOKEN_PRESENT` result
only means a substring survived; it is not evidence of valid or equivalent CSS.

These results are specific to the evaluated version and policies, not a claim that
AntiSamy is unsuitable for applications with a simpler HTML/CSS contract. Changing
the default policy alone did not recover the required features. Combining it with
our existing filtering would retain custom code and risk discarding supported CSS.

## Reproduce

Use the project's installed Chromium under `.runtime/browsers`. From this directory:

```powershell
mvn -B -ntp compile exec:java '-Dexec.mainClass=AntiSamyTrial'
```

The diagnostic policy is written under `target/`. This standalone Maven project is
excluded from the application's parent reactor and deployable artifacts. Build
success means the probe executed; review its output for compatibility results.

Upstream references: [AntiSamy](https://github.com/nahsra/antisamy),
[CSS scanner](https://github.com/nahsra/antisamy/blob/main/src/main/java/org/owasp/validator/css/CssScanner.java).
