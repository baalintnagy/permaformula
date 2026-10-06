// Java injects the validated shared policy before either engine loads its bundle.
export const limits = Object.freeze(globalThis.PermMathComplexity);
if (!limits) throw new Error('Missing rendering complexity policy');

export function validateMathInput(source) {
  if (source.length > limits.MATH_CHARS) throw new Error('Math expression exceeds size limit');
}

export function validateTree(root, children, nodeLimit, depthLimit, label) {
  const pending = [[root, 0]];
  let count = 0;
  while (pending.length) {
    const [node, depth] = pending.pop();
    if (++count > nodeLimit || depth > depthLimit)
      throw new Error(label + ' exceeds complexity limits');
    for (const child of children(node)) pending.push([child, depth + 1]);
  }
  return count;
}

export function validateDom(root) {
  return validateTree(
    root,
    (node) => node.childNodes,
    limits.RENDER_NODES,
    limits.RENDER_DEPTH,
    'Rendered DOM',
  );
}

export function validateDimensions(width, height) {
  if (
    !Number.isFinite(width) ||
    !Number.isFinite(height) ||
    width <= 0 ||
    height <= 0 ||
    width > limits.RENDER_DIMENSION ||
    height > limits.RENDER_DIMENSION ||
    width * height > limits.RENDER_AREA
  ) {
    throw new Error('Rendered dimensions exceed limits');
  }
}
