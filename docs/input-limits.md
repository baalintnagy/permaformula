# Input and complexity limits

Commands and source paths below are relative to the repository root.

All four endpoints reject invalid or oversized application payloads with HTTP 400.
Limits count UTF-8 bytes and preserve accepted content and whitespace:

| Input | Limit |
|---|---|
| `/exp-svg` plain formula | 384 KiB, nonblank |
| Descriptor HTML/content (`forumla`) | 6 MiB, nonblank |
| Inline stylesheet | 1.5 MiB |
| Stylesheet references | Eight, nonblank, 2 KiB each |
| Image width | 220â€“4000, when supplied |
| Decompressed/serialized descriptor JSON | 12 MiB |
| Compressed descriptor | 12 MiB + 16 KiB |
| Base64 descriptor | 16,799,064 characters |

The JSON decoder limits nesting to eight, tokens to 128, property names to 64
characters, string tokens to 6,291,456 characters and numbers to ten characters.
It rejects trailing JSON. Descriptor validation applies before image rendering
and cache identity generation. Malformed Base64, GZIP or JSON returns HTTP 400.
Decompression enforces one total expansion budget, including concatenated GZIP members.
Existing permalinks exceeding these limits are rejected. JSON escaping counts toward
the serialized JSON budget. Web-server/proxy URL limits may be lower and reject a
request before these application limits apply.

These limits bound individual inputs. API admission and deadlines also bound
concurrent preparation/fetching. jsoup's streaming parser bounds HTML construction;
ph-css tokenizes CSS before its recursive grammar is run. Normalized HTML and
MathJax's compiled trees/output are checked again. Application code does not lex
HTML, CSS characters or math delimiters.

| Complexity boundary | Cap |
| --- | --- |
| Submitted HTML | 20,000 elements, 60,000 nodes, depth 128, 64 attributes/element |
| Inline style attribute | 32 KiB |
| CSS per source | 20,000 rules/selectors, 100,000 declaration separators, 262,144 tokens |
| CSS structure | 32 block/bracket levels, 64 function levels, 4,096 selector/token characters |
| Combined stylesheets | 20,000 rules/selectors; selector count Ã— HTML element count â‰¤ 100 million |
| Numeric CSS grid repeat | 1,024 repetitions |
| Math input | 2,048 expressions/document; 393,216 characters/expression; formula endpoint also caps UTF-8 at 384 KiB |
| Compiled MathJax tree | 100,000 nodes/document; depth 128 |
| TeX expansion | 10,000 substitutions; 393,216-character buffer |
| Rendered DOM / SVG | 100,000 nodes/elements; depth 256; 16 MiB SVG |
| Rendered viewport/content | 32,768 pixels per dimension; 64 million pixels total |

Geometry is checked before SVG cloning/export, including overflowing content and
individual element bounds. Formula SVG dimensions are checked before serialization;
the API independently checks root SVG dimensions before caching. MathJax `ex`/`em`
dimensions use the renderer's 8/16-pixel conversion. Policy versions in cache identities
prevent reuse of artifacts generated under the earlier limits. These are structural
budgets, not a prediction of layout cost: isolated workers retain their 30-second
deadline, 1 GiB heap and container CPU/memory limits for expensive accepted inputs.

All complexity defaults live in
[complexity-defaults.properties](../render-contract/src/main/resources/complexity-defaults.properties).
Override a setting with a JVM option such as
`-Dpermaformula.complexity.render-dimension=24000` or the environment variable
`PERMAFORMULA_COMPLEXITY_RENDER_DIMENSION=24000`. JVM options take precedence over
environment variables. These are startup settings; use the same overrides in the
API and renderer services. For Compose, add them to both services' `environment`
sections; for Eclipse, use the corresponding VM arguments or Environment tabs.
They are not Spring `application.yaml` properties. Nonpositive/invalid values and
SVG budgets larger than the transport limit are rejected.

The renderer forwards its resolved policy to isolated worker JVMs and injects the same
values into Chromium and GraalJS before their bundles run. JavaScript contains no
duplicate defaults. The resolved policy fingerprint participates in cache keys.
MathJax supplies expression discovery and native TeX expansion limits; [AsciiMath](https://asciimath.org/)
has no native pre-parse nesting cap. Its byte/character budget, compiled-tree checks
and isolated worker deadline/memory limits remain necessary. Parser errors and
exceeded math budgets reject the render instead of exporting an error image.
