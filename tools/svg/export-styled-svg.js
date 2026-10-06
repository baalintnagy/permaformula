import {toSvg} from 'html-to-image';

// html-to-image handles DOM cloning, computed styles and foreignObject serialization.
// Network and output security remain enforced independently by the renderer and API.
window.exportStyledSvg = async function () {
  const source = document.body;
  const computed = getComputedStyle(source);
  const height = Math.max(1, Math.ceil(source.getBoundingClientRect().height +
      parseFloat(computed.marginTop) + parseFloat(computed.marginBottom)));
  const dataUrl = await toSvg(source, {
    width: window.innerWidth,
    height,
    // Canvas dimensions include margins; keep the body's original content-box dimensions.
    style: {width: computed.width, height: computed.height},
    skipFonts: true,
    filter: node => !['SCRIPT', 'LINK', 'STYLE'].includes(node.nodeName.toUpperCase())
  });
  const svg = new DOMParser().parseFromString(decodeURIComponent(dataUrl.substring(dataUrl.indexOf(',') + 1)),
      'image/svg+xml');
  // The library emits this obsolete SVG 1.1 attribute; no external resources are supported.
  svg.querySelector('foreignObject').removeAttribute('externalResourcesRequired');
  // Match the existing export contract: computed inline styles, no stylesheet nodes.
  // This also excludes pseudo-element styles synthesized by the library after cloning.
  svg.querySelectorAll('script,link,style').forEach(node => node.remove());
  return new XMLSerializer().serializeToString(svg.documentElement);
};
