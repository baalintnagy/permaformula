// TeX has SafeHandler filters; ASCIIMath needs its compiled tree filtered too.
const AFTER_COMPILE = 25;

export function safeMathOptions() {
  return {
    safeOptions: {
      allow: { URLs: 'safe', classes: 'safe', cssIDs: 'safe', styles: 'safe' },
      safeProtocols: { https: true, http: false, file: false, javascript: false, data: false },
    },
    renderActions: { safeAsciiMath: [AFTER_COMPILE, sanitizeDocument, sanitizeMath] },
  };
}

function sanitizeDocument(document) {
  for (const math of document.math) sanitizeMath(math, document);
}

function sanitizeMath(math, document) {
  document.safe.sanitize(math, document);
}
