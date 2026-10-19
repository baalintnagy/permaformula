import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const cli = join(dirname(require.resolve('playwright/package.json')), 'cli.js');
const browsers = fileURLToPath(new URL('../../.runtime/frontend-browsers/', import.meta.url));
const result = spawnSync(process.execPath, [cli, ...process.argv.slice(2)], {
    stdio: 'inherit',
    env: {
        ...process.env,
        PLAYWRIGHT_BROWSERS_PATH: process.env.PLAYWRIGHT_BROWSERS_PATH || browsers,
        PLAYWRIGHT_SKIP_BROWSER_GC: '1',
    },
    windowsHide: true,
});
if (result.error) {
    throw result.error;
}
process.exitCode = result.status ?? 1;
