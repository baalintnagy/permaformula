import DOMPurify from 'dompurify';
import { validStyleRef } from './api';

function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]!,
  );
}

export function previewDocument(
  {
    formula,
    html,
    css = '',
    styleRefs = [],
    imageWidth = 800,
  }: {
    formula?: string;
    html?: string;
    css?: string;
    styleRefs?: string[];
    imageWidth?: number;
  },
  origin = window.location.origin,
): string {
  const nonce = crypto.randomUUID().replaceAll('-', '');
  const assets = new URL('/mathjax/', origin).href;
  const safeHtml = DOMPurify.sanitize(html || '', {
    // Mirror the server's presentation-only HTML vocabulary. CSS policy remains server-authoritative.
    ALLOWED_TAGS: [
      'div',
      'span',
      'p',
      'br',
      'hr',
      'h1',
      'h2',
      'h3',
      'h4',
      'h5',
      'h6',
      'b',
      'strong',
      'i',
      'em',
      'u',
      's',
      'sub',
      'sup',
      'pre',
      'code',
      'blockquote',
      'ul',
      'ol',
      'li',
      'table',
      'thead',
      'tbody',
      'tfoot',
      'tr',
      'th',
      'td',
    ],
    ALLOWED_ATTR: ['class', 'title', 'dir', 'style', 'colspan', 'rowspan'],
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
  });
  // Serialize data as inert text. Neither HTML nor CSS can close a trusted script/style tag.
  const payload = JSON.stringify({ formula, css }).replaceAll('<', '\\u003c');
  const links = styleRefs
    .filter(validStyleRef)
    .map((ref) => `<link rel="stylesheet" href="${escapeHtml(ref)}">`)
    .join('');
  const csp = `default-src 'none'; script-src 'nonce-${nonce}' ${assets}; style-src 'unsafe-inline' https:; font-src https: data:; img-src 'none'; connect-src 'none'; base-uri 'none'; form-action 'none'`;
  return `<!doctype html><html><head><meta charset="utf-8"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="${escapeHtml(csp)}">
    <style>html{color-scheme:light;background:white}${formula !== undefined ? 'body{color:#172033;margin:0;padding:16px}#formula-preview{display:flex;align-items:center;justify-content:center;min-height:115px}#formula-preview svg{max-width:100%;height:auto}' : ''}</style>
    ${links}<style id="user-css"></style>
    <script nonce="${nonce}">
      const payload=${payload};
      document.getElementById('user-css').textContent=payload.css;
      window.MathJax={loader:{load:['input/asciimath','ui/safe'],paths:{mathjax:${JSON.stringify(assets.slice(0, -1))}}},
        options:{enableMenu:false,safeOptions:{allow:{URLs:'none',classes:'safe',cssIDs:'none',styles:'safe'}}},
        svg:{fontCache:'local'},startup:{typeset:false,ready(){
          MathJax.startup.defaultReady();
          MathJax.startup.promise.then(async()=>{
            if(payload.formula!==undefined){
              if(payload.formula.trim())document.getElementById('formula-preview').appendChild(await MathJax.asciimath2svgPromise(payload.formula));
              MathJax.startup.document.updateDocument();
            }else{await MathJax.typesetPromise([document.body]);}
            parent.postMessage({type:'permmath-preview',ok:true},'*');
          }).catch(()=>parent.postMessage({type:'permmath-preview',ok:false},'*'));
        }}};
    </script><script nonce="${nonce}" src="${escapeHtml(assets)}tex-svg.js"></script>
    </head><body>${formula !== undefined ? '<div id="formula-preview"></div>' : safeHtml}</body></html>`;
}
