import { cp, mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Use the exact browser distribution shipped with the renderer, without a CDN.
const source = new URL('../../renderer-core/src/main/resources/mathjax/', import.meta.url);
const target = new URL('../public/mathjax/', import.meta.url);
const targetPath = fileURLToPath(target);
if (!targetPath.startsWith(fileURLToPath(new URL('../public/', import.meta.url)))) {
    throw new Error('Unexpected generated MathJax destination');
}
await rm(targetPath, { recursive: true, force: true });
await mkdir(target, { recursive: true });
for (const path of [
    'startup.js',
    'core.js',
    'input',
    'output',
    'ui/safe.js',
    'a11y/assistive-mml.js',
    'fonts',
    'LICENSE',
]) {
    await cp(fileURLToPath(new URL(path, source)), fileURLToPath(new URL(path, target)), {
        recursive: true,
    });
}
