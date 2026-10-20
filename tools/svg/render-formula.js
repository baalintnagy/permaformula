import { mathjax } from '@mathjax/src/js/mathjax.js';
import { AsciiMath } from '@mathjax/src/js/input/asciimath.js';
import { SVG } from '@mathjax/src/js/output/svg.js';
import { liteAdaptor } from '@mathjax/src/js/adaptors/liteAdaptor.js';
import { RegisterHTMLHandler } from '@mathjax/src/js/handlers/html.js';
import { SafeHandler } from '@mathjax/src/js/ui/safe/SafeHandler.js';
import { MathJaxNewcmFont } from '@mathjax/mathjax-newcm-font/js/svg.js';
import 'permaformula-font-data';
import { safeMathOptions, installMathValidation } from './mathjax-safety.js';
import { limits, validateTree, validateDimensions } from './complexity-limits.js';

const adaptor = liteAdaptor();
SafeHandler(RegisterHTMLHandler(adaptor));
const font = new MathJaxNewcmFont();
for (const data of Object.values(MathJaxNewcmFont.dynamicFiles)) {
    data.setup(font);
}
const document = mathjax.document('', {
    InputJax: new AsciiMath(),
    OutputJax: new SVG({ fontCache: 'local', fontData: font }),
    ...safeMathOptions({ discoverInputs: false }),
});
installMathValidation(document);

export function render(formula) {
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
    if (result.length > limits.SVG_BYTES) {
        throw new Error('SVG exceeds size limit');
    }
    return result;
}
