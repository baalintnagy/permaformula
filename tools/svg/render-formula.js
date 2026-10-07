import { mathjax } from 'mathjax-full/js/mathjax.js';
import { AsciiMath } from 'mathjax-full/js/input/asciimath.js';
import { SVG } from 'mathjax-full/js/output/svg.js';
import { liteAdaptor } from 'mathjax-full/js/adaptors/liteAdaptor.js';
import { RegisterHTMLHandler } from 'mathjax-full/js/handlers/html.js';
import { SafeHandler } from 'mathjax-full/js/ui/safe/SafeHandler.js';
import { safeMathOptions } from './mathjax-safety.js';
import {
  limits,
  validateMathInput,
  validateTree,
  validateDimensions,
} from './complexity-limits.js';

const adaptor = liteAdaptor();
SafeHandler(RegisterHTMLHandler(adaptor));
const document = mathjax.document('', {
  InputJax: new AsciiMath(),
  OutputJax: new SVG({ fontCache: 'local' }),
  ...safeMathOptions(),
});

export function render(formula) {
  validateMathInput(formula);
  const container = document.convert(formula, { display: true });
  const svg = adaptor.firstChild(container);
  validateTree(
    svg,
    (node) => adaptor.childNodes(node),
    limits.RENDER_NODES,
    limits.RENDER_DEPTH,
    'Math SVG',
  );
  const width = Number.parseFloat(adaptor.getAttribute(svg, 'width')) * limits.EX_PIXELS;
  const height = Number.parseFloat(adaptor.getAttribute(svg, 'height')) * limits.EX_PIXELS;
  validateDimensions(width, height);
  const result = adaptor.outerHTML(svg);
  if (result.length > limits.SVG_BYTES) throw new Error('SVG exceeds size limit');
  return result;
}
