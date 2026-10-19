# SVG bundles

This bundles MathJax 3.2.2's [AsciiMath](https://asciimath.org/) input, SVG output, and lightweight DOM
adapter into a script for GraalJS. No browser, Node process, network access,
or JavaScript host access is needed at runtime.

MathJax's `SafeHandler` restricts generated URLs and styles. A render action also
applies its safe filtering to [AsciiMath](https://asciimath.org/) trees, because the upstream TeX filters do
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
rebuilds the formula, HTML and browser-guard bundles. No Node process is needed at runtime.

The HTML exporter delegates DOM cloning, computed-style copying and SVG serialization
to html-to-image. The adapter preserves our output dimensions, disables font downloads,
filters script/link/style nodes and omits an obsolete foreignObject attribute. Images
and remote resources remain restricted by the existing input and browser policies.
Pseudo-element stylesheet nodes are omitted, matching the previous exporter contract.
Final SVG is still independently validated in the API. The library's MIT license is
included in `renderer-core/src/main/resources/html-to-image-LICENSE`.

## Source organization

| Source | Purpose |
|---|---|
| `render-formula.js` | GraalJS [AsciiMath](https://asciimath.org/) entry point, exposed as `PermaFormulaSvg.render`. |
| `browser-entry.js` | Browser MathJax setup, readiness, export and error handling. |
| `browser-assets.js` | Font/image readiness and image listener cleanup. |
| `export-styled-svg.js` | Named functions for content measurement and html-to-image export normalization. |
| `mathjax-safety.js` | Shared safe protocols and [AsciiMath](https://asciimath.org/) render action for both entry points. |
| `complexity-limits.js` | Shared policy, tree, input and dimension validation. |
| `browser-guards.js` | Browser channel restrictions installed before page scripts. |
| `build.mjs` | Build or verify checked-in bundles with esbuild. |

The browser communicates with Java through `window.PermaFormulaRenderer` (`state`, `svg`,
`error`). The HTML template contains only references to trusted scripts; it contains
no application JavaScript or inline event handlers. Playwright loads the generated
guard resource directly, so it does not depend on the page or asset server.

## Checks

```sh
npm run format
npm run build
npm run check
```

`check` runs ESLint, Prettier's formatting check, Node asset-readiness and complexity
boundary tests, and an in-memory esbuild comparison
against all three checked-in bundles. It fails on missing/stale bundles without
rewriting them. Generated files carry notices identifying their source; do not
format or lint vendor bundles. Dependencies are pinned in `package-lock.json`.
Run these checks before the browser-enabled Maven verification. Node is required
for JavaScript development checks only; ordinary Maven builds use the checked-in
resources. The full MathJax distribution remains intact for dynamic extension loading.
