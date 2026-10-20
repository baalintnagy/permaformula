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
        imageWidth = 500,
        dark = false,
        externalStylesAllowed = false,
    }: {
        formula?: string;
        html?: string;
        css?: string;
        styleRefs?: string[];
        imageWidth?: number;
        dark?: boolean;
        externalStylesAllowed?: boolean;
    },
    origin = window.location.origin,
): string {
    // randomUUID is restricted to secure contexts; LAN demos also run over HTTP.
    const nonce = Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) =>
        byte.toString(16).padStart(2, '0'),
    ).join('');
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
    const links = (externalStylesAllowed ? styleRefs : [])
        .filter(validStyleRef)
        .map((ref) => `<link rel="stylesheet" href="${escapeHtml(ref)}">`)
        .join('');
    const csp = `default-src 'none'; script-src 'nonce-${nonce}' ${assets}; style-src 'unsafe-inline'${externalStylesAllowed ? ' https:' : ''}; font-src ${externalStylesAllowed ? 'https: data:' : 'data:'}; img-src 'none'; connect-src 'none'; base-uri 'none'; form-action 'none'`;
    return `<!doctype html><html><head><meta charset="utf-8"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="${escapeHtml(csp)}">
    <style>${formula !== undefined ? `html{color-scheme:${dark ? 'dark' : 'light'};background:${dark ? '#14151a' : '#e7e7ec'};color:${dark ? '#e4e9f4' : '#172033'}}body{color:inherit;margin:0;padding:16px;box-sizing:border-box;min-height:100vh}#formula-preview{display:flex;align-items:center;justify-content:center;min-height:calc(100vh - 32px);width:max-content;min-width:100%}#formula-preview svg{max-width:none;height:auto}` : 'html{color-scheme:light;background:white;color:black}'}</style>
    ${links}<style id="user-css"></style>
    ${formula !== undefined ? `<style>html[data-theme='light']{color-scheme:light;background:#e7e7ec;color:#172033}html[data-theme='dark']{color-scheme:dark;background:#14151a;color:#e4e9f4}@media(prefers-reduced-motion:no-preference){html.theme-transition{transition:background-color 800ms ease,color 800ms ease}}</style>` : ''}
    <script nonce="${nonce}">
      const payload=${payload};
      const loaded=new Promise(resolve=>window.addEventListener('load',resolve,{once:true}));
      window.addEventListener('message',event=>{
        if(event.source!==parent || event.data?.type!=='permaformula-theme' || payload.formula===undefined){return;}
        if(typeof event.data.dark!=='boolean'){return;}
        const root=document.documentElement;
        root.classList.toggle('theme-transition',event.data.animate===true);
        root.dataset.theme=event.data.dark?'dark':'light';
      });
      window.addEventListener('message',event=>{
        if(event.source!==parent || event.data?.type!=='permaformula-zoom' || payload.formula===undefined)return;
        const zoom=event.data.zoom;
        const preview=document.getElementById('formula-preview');
        if(preview && typeof zoom==='number' && Number.isFinite(zoom) && zoom>=10 && zoom<=1000){
          preview.style.fontSize=zoom+'%';
        }
      });
      // Report scroll from inside the opaque sandbox, including nested HTML scrollers.
      const scrollers=new Set();
      function scrollPath(element){
        const path=[];
        while(element!==document.body){
          const parent=element.parentElement;
          if(!parent || path.length>=64){return null;}
          path.unshift(Array.prototype.indexOf.call(parent.children,element));
          element=parent;
        }
        return path;
      }
      function reportScroll(){
        try{
          const elements=Array.from(scrollers).filter(element=>element.isConnected).map(element=>({
            path:scrollPath(element),tag:element.tagName,classes:element.className,
            left:element.scrollLeft,top:element.scrollTop
          }));
          parent.postMessage({type:'permaformula-preview-scroll',scroll:{left:scrollX,top:scrollY,elements}},'*');
        }catch{}
      }
      window.addEventListener('scroll',event=>{
        const target=event.target;
        if(target instanceof HTMLElement && target!==document.scrollingElement && scrollers.size<128){
          scrollers.add(target);
        }
        reportScroll();
      },{capture:true,passive:true});
      const scrollValue=value=>typeof value==='number' && Number.isFinite(value) && Math.abs(value)<=1e9;
      window.addEventListener('message',event=>{
        if(event.source!==parent || event.data?.type!=='permaformula-restore-scroll'){return;}
        const saved=event.data.scroll;
        try{
          if(saved && scrollValue(saved.left) && scrollValue(saved.top)){
            window.scrollTo({left:saved.left,top:saved.top,behavior:'instant'});
          }
        }catch{}
        if(Array.isArray(saved?.elements)){
          for(const entry of saved.elements.slice(0,128)){
            try{
              if(!Array.isArray(entry?.path) || entry.path.length>64 || !scrollValue(entry.left) || !scrollValue(entry.top)){continue;}
              let element=document.body;
              for(const index of entry.path){
                if(!Number.isInteger(index) || index<0){element=null;break;}
                element=element?.children[index];
              }
              if(element instanceof HTMLElement && element.tagName===entry.tag && element.className===entry.classes){
                element.scrollTo({left:entry.left,top:entry.top,behavior:'instant'});
                scrollers.add(element);
              }
            }catch{}
          }
        }
        reportScroll();
      });
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
            await loaded;
            await document.fonts.ready;
            parent.postMessage({type:'permaformula-preview',ok:true},'*');
          }).catch(()=>parent.postMessage({type:'permaformula-preview',ok:false},'*'));
        }}};
    </script><script nonce="${nonce}" src="${escapeHtml(assets)}tex-svg.js"></script>
    </head><body>${formula !== undefined ? '<div id="formula-preview"></div>' : safeHtml}</body></html>`;
}
