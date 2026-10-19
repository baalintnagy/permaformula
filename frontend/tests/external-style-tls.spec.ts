import { expect, test } from '@playwright/test';
import https from 'node:https';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Disposable local TLS fixture only; no public host or production trust setting.
test.use({ ignoreHTTPSErrors: true });
let server: https.Server;
let temporary: string;
let origin: string;
let requests: string[];
test.beforeAll(async () => {
    temporary = mkdtempSync(join(tmpdir(), 'permaformula-preview-'));
    const openssl =
        process.platform === 'win32' && existsSync('C:/Git/usr/bin/openssl.exe')
            ? 'C:/Git/usr/bin/openssl.exe'
            : 'openssl';
    execFileSync(
        openssl,
        [
            'req',
            '-x509',
            '-newkey',
            'rsa:2048',
            '-nodes',
            '-days',
            '1',
            '-subj',
            '/CN=localhost',
            '-keyout',
            join(temporary, 'key.pem'),
            '-out',
            join(temporary, 'cert.pem'),
        ],
        { stdio: 'ignore' },
    );
    server = https.createServer(
        {
            key: readFileSync(join(temporary, 'key.pem')),
            cert: readFileSync(join(temporary, 'cert.pem')),
        },
        (req, res) => {
            requests.push(req.url!);
            res.setHeader('Access-Control-Allow-Origin', '*');
            res.setHeader('Content-Type', req.url!.endsWith('.woff2') ? 'font/woff2' : 'text/css');
            res.end(
                req.url === '/main.css'
                    ? `@import url("${origin}/nested.css"); p { color: rgb(12, 34, 56); }`
                    : '',
            );
        },
    );
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    origin = `https://127.0.0.1:${(server.address() as { port: number }).port}`;
});
test.afterAll(async () => {
    if (server) {
        server.closeAllConnections();
        await new Promise<void>((resolve) => server.close(() => resolve()));
    }
    if (temporary) {
        rmSync(temporary, { recursive: true, force: true });
    }
});
test.beforeEach(() => {
    requests = [];
});

test('shared preview loads HTTPS styles and fonts only after clicking Load styles', async ({
    page,
}) => {
    const descriptor = {
        forumla: '<p>Shared content</p>',
        style: `@import url("${origin}/inline.css"); @font-face { font-family: ConsentFont; src: url("${origin}/test.woff2"); } p { font-family: ConsentFont; }`,
        styleRefs: [origin + '/main.css'],
        imageWidth: 500,
    };
    await page.route('**/library-item?**', (route) =>
        route.fulfill({
            json: {
                id: 'a'.repeat(64),
                ubcq: 'H4sI+a/b+/==',
                kind: 'html',
                descriptor,
                createdAt: 1,
            },
        }),
    );
    await page.route('**/html-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="500" height="100"/>',
        }),
    );
    await page.goto('/?libraryItem=' + 'a'.repeat(64) + '#composer');
    const preview = page.frameLocator('iframe[title="HTML live preview"]');
    await expect(preview.locator('p')).toHaveText('Shared content');

    await expect(page.getByRole('textbox', { name: 'HTML source', exact: true })).toContainText(
        'Shared content',
    );
    expect(requests).toEqual([]);
    await page.getByRole('button', { name: 'Load styles', exact: true }).click();
    await expect(preview.locator('p')).toHaveCSS('color', 'rgb(12, 34, 56)');
    await expect.poll(() => requests.includes('/test.woff2')).toBe(true);
    expect(requests).toEqual(expect.arrayContaining(['/main.css', '/inline.css', '/nested.css']));
    await page.route('**/ubcq-decode?**', (route) =>
        route.fulfill({
            json: {
                ...descriptor,
                styleRefs: [origin + '/second.css'],
                style: `@import url("${origin}/second-import.css");`,
            },
        }),
    );
    await page.getByLabel('UBCQ or URL', { exact: true }).fill('H4sIabc==');
    await page.getByRole('button', { name: 'Decode and edit', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Load styles', exact: true })).toBeVisible();
    await expect(preview.locator('p')).toHaveCSS('color', 'rgb(0, 0, 0)');
    expect(requests.some((url) => url.includes('second'))).toBe(false);
    await page.getByRole('button', { name: 'Load styles', exact: true }).click();
    await expect.poll(() => requests.includes('/second.css')).toBe(true);
    await expect.poll(() => requests.includes('/second-import.css')).toBe(true);
});

test('manually entered stylesheet references wait for explicit loading', async ({ page }) => {
    await page.goto('/#composer');
    await page.getByRole('button', { name: 'Add stylesheet', exact: true }).click();
    await page.getByRole('textbox', { name: /Stylesheet URL/ }).fill(origin + '/manual.css');
    expect(requests).toEqual([]);
    await page.getByRole('button', { name: 'Load styles', exact: true }).click();
    await expect.poll(() => requests.includes('/manual.css')).toBe(true);
    await expect(page.getByRole('button', { name: 'Load styles', exact: true })).toHaveCount(0);
    await page.getByRole('textbox', { name: /Stylesheet URL/ }).fill(origin + '/changed.css');
    await expect(page.getByRole('button', { name: 'Load styles', exact: true })).toBeVisible();
    expect(requests.includes('/changed.css')).toBe(false);
    await page.getByRole('button', { name: 'Remove stylesheet 1', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Load styles', exact: true })).toHaveCount(0);
    await page.getByRole('button', { name: 'Add stylesheet', exact: true }).click();
    await page.getByRole('textbox', { name: /Stylesheet URL/ }).fill(origin + '/manual.css');
    await expect(page.getByRole('button', { name: 'Load styles', exact: true })).toBeVisible();
});
