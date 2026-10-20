import { exportStyledSvg } from './export-styled-svg.js';
import { safeMathOptions, installMathValidation } from './mathjax-safety.js';
import { limits, validateDom } from './complexity-limits.js';
import { waitForAssets } from './browser-assets.js';

const renderer = { state: 'loading', svg: null, error: null };
window.PermaFormulaRenderer = renderer;
window.MathJax = {
    loader: {
        load: ['input/tex', 'input/asciimath', 'output/svg', 'ui/safe', 'a11y/assistive-mml'],
    },
    output: { fontPath: '/mathjax/fonts/%%FONT%%-font', linebreaks: { inline: false } },
    options: safeMathOptions(),
    startup: {
        ready() {
            window.MathJax.startup.defaultReady();
            installMathValidation(window.MathJax.startup.document);
        },
        pageReady: renderPage,
    },
    svg: { fontCache: 'local' },
    tex: { maxMacros: limits.TEX_MACROS, maxBuffer: limits.TEX_BUFFER },
};
window.addEventListener('error', handleScriptError, true);

async function renderPage() {
    try {
        validateDom(document.body);
        await window.MathJax.startup.defaultPageReady();
        await waitForAssets(document);
        validateDom(document.body);
        if (document.querySelector('mjx-merror')) {
            throw new Error('Invalid math expression');
        }
        const svg = await exportStyledSvg();
        if (renderer.state !== 'error') {
            renderer.svg = svg;
            renderer.state = 'ready';
        }
    } catch (error) {
        failRender(error);
    }
}

function handleScriptError(event) {
    if (event.target instanceof HTMLScriptElement && event.target.id === 'mathjax-script') {
        failRender(new Error('MathJax failed to load'));
    }
}

function failRender(error) {
    renderer.svg = null;
    renderer.error = String(error);
    renderer.state = 'error';
}
