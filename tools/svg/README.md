# Formula SVG bundle

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
