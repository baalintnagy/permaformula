// Use MathJax's discovered expressions and compiled MML tree; no delimiter/math lexer.
import { limits, validateMathInput, validateTree } from './complexity-limits.js';
const BEFORE_COMPILE = 15;
const AFTER_COMPILE = 25;

export function safeMathOptions() {
  return {
    safeOptions: {
      allow: { URLs: 'safe', classes: 'safe', cssIDs: 'safe', styles: 'safe' },
      safeProtocols: { https: true, http: false, file: false, javascript: false, data: false },
    },
    renderActions: {
      checkMathInputs: [BEFORE_COMPILE, validateInputs, validateInput],
      safeAsciiMath: [AFTER_COMPILE, sanitizeDocument, sanitizeItem],
    },
  };
}

function validateInputs(document) {
  let count = 0;
  for (const math of document.math) {
    if (++count > limits.MATH_EXPRESSIONS) throw new Error('Math expression count exceeds limit');
    validateInput(math);
  }
}

function validateInput(math) {
  validateMathInput(math.math);
}

function sanitizeDocument(document) {
  let nodes = 0;
  for (const math of document.math) {
    nodes += sanitizeMath(math, document);
    if (nodes > limits.MATH_NODES) throw new Error('Math document exceeds complexity limits');
  }
}

function sanitizeMath(math, document) {
  const count = validateTree(
    math.root,
    (node) => node.childNodes,
    limits.MATH_NODES,
    limits.MATH_DEPTH,
    'Compiled math',
  );
  // Native parser errors (including TeX expansion limits) reject rather than export an error image.
  math.root.walkTree((node) => {
    if (node.kind === 'merror') throw new Error('Invalid math expression');
  });
  document.safe.sanitize(math, document);
  return count;
}

function sanitizeItem(math, document) {
  sanitizeMath(math, document);
}
