// Freeze the browser's HTML/CSS layout into an SVG foreignObject.
window.exportStyledSvg = async function () {
  const svgNS = 'http://www.w3.org/2000/svg';
  const htmlNS = 'http://www.w3.org/1999/xhtml';
  const source = document.body;
  const copy = source.cloneNode(true);
  const originals = [source, ...source.querySelectorAll('*')];
  const copies = [copy, ...copy.querySelectorAll('*')];
  const resources = new Map();
  async function embed(url) {
    if (url.startsWith('data:') || url.startsWith('#')) return url;
    if (!resources.has(url)) resources.set(url, (async function () {
      const response = await fetch(url);
      if (!response.ok) throw new Error('Unable to embed resource: ' + url);
      const blob = await response.blob();
      return await new Promise(function (resolve, reject) {
        const reader = new FileReader();
        reader.onload = function () { resolve(reader.result); };
        reader.onerror = reject;
        reader.readAsDataURL(blob);
      });
    })());
    return await resources.get(url);
  }
  for (let i = 0; i < originals.length; i++) {
    const original = originals[i], clone = copies[i];
    const computed = getComputedStyle(original);
    let css = '';
    for (const property of computed) {
      // WebView can report default CSS geometry even when SVG presentation
      // attributes position the glyph. Inlining it would override those attributes.
      if (original.namespaceURI === svgNS && original.hasAttribute(property) &&
          ['transform', 'x', 'y', 'cx', 'cy', 'r', 'rx', 'ry', 'width', 'height'].includes(property)) continue;
      let value = computed.getPropertyValue(property);
      const urls = [...value.matchAll(/url\(["']?([^"')]+)["']?\)/g)];
      for (const match of urls) {
        if (!match[1].startsWith('#')) value = value.replace(match[0], 'url("' + await embed(match[1]) + '")');
      }
      css += property + ':' + value + ';';
    }
    // Glyph definitions inherit paint from each <use>; freezing their default
    // fill would override the expression's color when the SVG is displayed.
    if (!(original.namespaceURI === svgNS && original.closest('defs'))) {
      clone.setAttribute('style', css);
    }
    for (const attribute of [...clone.attributes]) {
      if (attribute.name.toLowerCase().startsWith('on')) clone.removeAttribute(attribute.name);
    }
    if (original.tagName === 'IMG') {
      clone.setAttribute('src', await embed(original.currentSrc || original.src));
      clone.removeAttribute('srcset');
    }
  }
  copy.querySelectorAll('script,link,style').forEach(function (node) { node.remove(); });
  const width = window.innerWidth;
  const bodyStyle = getComputedStyle(source);
  const height = Math.max(1, Math.ceil(source.getBoundingClientRect().height +
      parseFloat(bodyStyle.marginTop) + parseFloat(bodyStyle.marginBottom)));
  const svg = document.createElementNS(svgNS, 'svg');
  svg.setAttribute('width', width);
  svg.setAttribute('height', height);
  svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
  const foreign = document.createElementNS(svgNS, 'foreignObject');
  foreign.setAttribute('width', '100%');
  foreign.setAttribute('height', '100%');
  const wrapper = document.createElementNS(htmlNS, 'div');
  wrapper.setAttribute('style', 'width:' + width + 'px;height:' + height + 'px;background:white;overflow:hidden;');
  wrapper.appendChild(copy);
  foreign.appendChild(wrapper);
  svg.appendChild(foreign);
  return new XMLSerializer().serializeToString(svg);
};
