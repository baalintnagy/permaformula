import { cp, mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Use the exact browser distribution shipped with the renderer, without a CDN.
const source = new URL('../../renderer-core/src/main/resources/mathjax/', import.meta.url);
const target = new URL('../public/mathjax/', import.meta.url);
await mkdir(target, { recursive: true });
for (const path of ['tex-svg.js', 'input/asciimath.js', 'ui/safe.js', 'LICENSE']) {
    await cp(fileURLToPath(new URL(path, source)), fileURLToPath(new URL(path, target)), {
        recursive: true,
    });
}
