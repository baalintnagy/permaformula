import { exportStyledSvg } from './export-styled-svg.js';
import { safeMathOptions } from './mathjax-safety.js';

const renderer = { state: 'loading', svg: null, error: null };
window.PermMathRenderer = renderer;
window.MathJax = {
  loader: { load: ['input/asciimath', 'ui/safe'] },
  options: safeMathOptions(),
  startup: { pageReady: renderPage },
  svg: { fontCache: 'local' },
};
window.addEventListener('error', handleScriptError, true);

async function renderPage() {
  try {
    await window.MathJax.startup.defaultPageReady();
    await waitForAssets();
    if (document.querySelector('mjx-merror')) throw new Error('Invalid math expression');
    renderer.svg = await exportStyledSvg();
    renderer.state = 'ready';
  } catch (error) {
    failRender(error);
  }
}

async function waitForAssets() {
  await Promise.all([document.fonts.ready, ...Array.from(document.images, waitForImage)]);
}

function waitForImage(image) {
  if (image.complete) {
    return image.naturalWidth
      ? Promise.resolve()
      : Promise.reject(new Error('Image failed: ' + image.src));
  }
  return new Promise((resolve, reject) => {
    image.addEventListener('load', resolve, { once: true });
    image.addEventListener('error', () => reject(new Error('Image failed: ' + image.src)), {
      once: true,
    });
  });
}

function handleScriptError(event) {
  if (event.target instanceof HTMLScriptElement && event.target.id === 'mathjax-script') {
    failRender(new Error('MathJax failed to load'));
  }
}

function failRender(error) {
  renderer.error = String(error);
  renderer.state = 'error';
}
