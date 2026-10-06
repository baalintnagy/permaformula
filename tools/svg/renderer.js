import {mathjax} from 'mathjax-full/js/mathjax.js';
import {AsciiMath} from 'mathjax-full/js/input/asciimath.js';
import {SVG} from 'mathjax-full/js/output/svg.js';
import {liteAdaptor} from 'mathjax-full/js/adaptors/liteAdaptor.js';
import {RegisterHTMLHandler} from 'mathjax-full/js/handlers/html.js';
import {SafeHandler} from 'mathjax-full/js/ui/safe/SafeHandler.js';

const adaptor = liteAdaptor();
SafeHandler(RegisterHTMLHandler(adaptor));
const document = mathjax.document('', {
  InputJax: new AsciiMath(),
  OutputJax: new SVG({fontCache: 'local'}),
  safeOptions: {
    allow: {URLs: 'safe', classes: 'safe', cssIDs: 'safe', styles: 'safe'},
    safeProtocols: {https: true, http: false, file: false, javascript: false, data: false}
  },
  renderActions: {
    // SafeHandler supplies TeX filters, but ASCIIMath also needs its compiled tree filtered.
    safeAsciiMath: [25, () => {}, (math, doc) => {doc.safe.sanitize(math, doc);}]
  }
});

export function render(formula) {
  const container = document.convert(formula, {display: true});
  return adaptor.outerHTML(adaptor.firstChild(container));
}
