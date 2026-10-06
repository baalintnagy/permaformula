import { mathjax } from 'mathjax-full/js/mathjax.js';
import { AsciiMath } from 'mathjax-full/js/input/asciimath.js';
import { SVG } from 'mathjax-full/js/output/svg.js';
import { liteAdaptor } from 'mathjax-full/js/adaptors/liteAdaptor.js';
import { RegisterHTMLHandler } from 'mathjax-full/js/handlers/html.js';
import { SafeHandler } from 'mathjax-full/js/ui/safe/SafeHandler.js';
import { safeMathOptions } from './mathjax-safety.js';

const adaptor = liteAdaptor();
SafeHandler(RegisterHTMLHandler(adaptor));
const document = mathjax.document('', {
  InputJax: new AsciiMath(),
  OutputJax: new SVG({ fontCache: 'local' }),
  ...safeMathOptions(),
});

export function render(formula) {
  const container = document.convert(formula, { display: true });
  return adaptor.outerHTML(adaptor.firstChild(container));
}
