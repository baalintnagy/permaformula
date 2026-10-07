import { toSvg } from 'html-to-image';
import { limits, validateDom, validateDimensions } from './complexity-limits.js';

// html-to-image handles DOM cloning, computed styles and foreignObject serialization.
// Network and output security remain enforced independently by the renderer and API.
export async function exportStyledSvg() {
  const source = document.body;
  validateDom(source);
  const dimensions = measureContent(source);
  validateDimensions(dimensions.width, dimensions.height);
  const dataUrl = await toSvg(source, {
    ...dimensions,
    skipFonts: true,
    filter: includeNode,
  });
  if (dataUrl.length > limits.SVG_BYTES * 3 + 128) throw new Error('SVG exceeds size limit');
  const result = normalizeSvg(decodeSvg(dataUrl));
  if (result.length > limits.SVG_BYTES) throw new Error('SVG exceeds size limit');
  return result;
}

function measureContent(source) {
  const computed = getComputedStyle(source);
  const bounds = source.getBoundingClientRect();
  const height = Math.max(
    1,
    Math.ceil(
      bounds.height +
        Number.parseFloat(computed.marginTop) +
        Number.parseFloat(computed.marginBottom),
    ),
  );
  const width = window.innerWidth;
  validateDimensions(
    Math.max(width, source.scrollWidth, bounds.width),
    Math.max(height, source.scrollHeight),
  );
  for (const element of source.querySelectorAll('*')) {
    const rectangle = element.getBoundingClientRect();
    if (
      ![rectangle.x, rectangle.y, rectangle.width, rectangle.height].every(Number.isFinite) ||
      Math.abs(rectangle.x) > limits.ELEMENT_POSITION ||
      Math.abs(rectangle.y) > limits.ELEMENT_POSITION ||
      rectangle.width > limits.RENDER_DIMENSION ||
      rectangle.height > limits.RENDER_DIMENSION
    ) {
      throw new Error('Element geometry exceeds limits');
    }
  }
  return {
    width,
    height,
    // Canvas dimensions include margins; keep the body's original content-box dimensions.
    style: { width: computed.width, height: computed.height },
  };
}

function includeNode(node) {
  return !['SCRIPT', 'LINK', 'STYLE'].includes(node.nodeName.toUpperCase());
}

function decodeSvg(dataUrl) {
  const prefix = 'data:image/svg+xml;charset=utf-8,';
  if (!dataUrl.startsWith(prefix)) throw new Error('Invalid SVG data URL');
  const markup = decodeURIComponent(dataUrl.slice(prefix.length));
  const svg = new DOMParser().parseFromString(markup, 'image/svg+xml');
  if (
    svg.querySelector('parsererror') ||
    svg.documentElement.localName !== 'svg' ||
    svg.documentElement.namespaceURI !== 'http://www.w3.org/2000/svg'
  ) {
    throw new Error('Invalid SVG document');
  }
  return svg;
}

function normalizeSvg(svg) {
  // The library emits this obsolete SVG 1.1 attribute; no external resources are supported.
  svg.querySelectorAll('foreignObject').forEach((node) => {
    node.removeAttribute('externalResourcesRequired');
  });
  // Match the existing export contract: computed inline styles, no stylesheet nodes.
  // This also excludes pseudo-element styles synthesized by the library after cloning.
  svg.querySelectorAll('script,link,style').forEach((node) => node.remove());
  return new XMLSerializer().serializeToString(svg.documentElement);
}
