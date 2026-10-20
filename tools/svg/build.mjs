import { build } from 'esbuild';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { copyMathJax } from './mathjax-assets.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const resources = resolve(directory, '../../renderer-core/src/main/resources');
const checking = process.argv.includes('--check');
// GraalJS has no module loader: include every SVG glyph table at build time.
const fontDirectory = resolve(
    directory,
    'node_modules/@mathjax/mathjax-newcm-font/mjs/svg/dynamic',
);
const fontFiles = (await readdir(fontDirectory)).filter((file) => file.endsWith('.js')).sort();
const fontPlugin = {
    name: 'offline-mathjax-font',
    setup(builder) {
        builder.onResolve({ filter: /^permaformula-font-data$/ }, () => ({
            path: 'permaformula-font-data',
            namespace: 'offline-font',
        }));
        builder.onLoad({ filter: /.*/, namespace: 'offline-font' }, () => ({
            contents: fontFiles
                .map((file) => `import ${JSON.stringify(`${fontDirectory}/${file}`)};`)
                .join('\n'),
            resolveDir: directory,
        }));
    },
};
const selected = process.argv.find((argument) => ['formula', 'html', 'guards'].includes(argument));
if (!selected) {
    await copyMathJax(checking);
}
const bundles = [
    {
        name: 'formula',
        source: 'render-formula.js',
        output: 'svg/renderer.js',
        options: {
            globalName: 'PermaFormulaSvg',
            define: { global: 'globalThis' },
        },
    },
    { name: 'html', source: 'browser-entry.js', output: 'export-svg.js' },
    { name: 'guards', source: 'browser-guards.js', output: 'browser-guards.js' },
];

for (const bundle of bundles.filter((bundle) => !selected || bundle.name === selected)) {
    const destination = resolve(resources, bundle.output);
    const result = await build({
        absWorkingDir: directory,
        entryPoints: [bundle.source],
        outfile: destination,
        bundle: true,
        format: 'iife',
        platform: 'browser',
        write: false,
        plugins: [fontPlugin],
        banner: {
            js: `// Generated from tools/svg/${bundle.source}; run npm run build. Do not edit.`,
        },
        ...bundle.options,
    });
    const generated = result.outputFiles[0].text;
    if (checking) {
        const committed = await readFile(destination, 'utf8');
        if (committed.replaceAll('\r\n', '\n') !== generated.replaceAll('\r\n', '\n')) {
            throw new Error(`Stale bundle: ${bundle.output}. Run npm run build.`);
        }
    } else {
        await mkdir(dirname(destination), { recursive: true });
        await writeFile(destination, generated);
    }
    console.log(`${checking ? 'Verified' : 'Built'} ${bundle.output}`);
}
