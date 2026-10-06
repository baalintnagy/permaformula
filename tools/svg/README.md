# SVG bundles

This bundles MathJax 3.2.2's ASCIIMath input, SVG output, and lightweight DOM
adapter into a script for GraalJS. No browser, Node process, network access,
or JavaScript host access is needed at runtime.

MathJax's `SafeHandler` restricts generated URLs and styles. A render action also
applies its safe filtering to ASCIIMath trees, because the upstream TeX filters do
not cover that input processor. HTTPS hyperlinks and local glyph fragments remain
supported. The API independently validates the resulting SVG before caching it.

The generated `renderer-core/src/main/resources/svg/renderer.js` is checked in. Node.js is
only needed to rebuild it after changing the bundle:

```sh
cd tools/svg
npm ci
npm run build
```

MathJax's upstream license is in `renderer-core/src/main/resources/mathjax/LICENSE`.

`export-styled-svg.js` is the source adapter for html-to-image 1.11.13. Its bundled
output is `renderer-core/src/main/resources/export-svg.js`; edit the adapter and
run `npm run build:html`, rather than editing the generated file. `npm run build`
rebuilds both the formula and HTML exporters. No Node process is needed at runtime.

The HTML exporter delegates DOM cloning, computed-style copying and SVG serialization
to html-to-image. The adapter preserves our output dimensions, disables font downloads,
filters script/link/style nodes and omits an obsolete foreignObject attribute. Images
and remote resources remain restricted by the existing input and browser policies.
Pseudo-element stylesheet nodes are omitted, matching the previous exporter contract.
Final SVG is still independently validated in the API. The library's MIT license is
included in `renderer-core/src/main/resources/html-to-image-LICENSE`.
