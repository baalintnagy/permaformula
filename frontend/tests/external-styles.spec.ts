import { expect, test } from '@playwright/test';

const descriptor = {
    forumla: '<p class="shared">Shared content</p>',
    style: '@import "https://styles.example/import.css"; @font-face { font-family: remote; src: url(https://styles.example/font.woff2); } p { font-family: remote; color: red; }',
    styleRefs: ['https://styles.example/main.css'],
    imageWidth: 500,
};

for (const source of ['decode', 'public library', 'admin library']) {
    test(`${source} waits for Load styles and resets when references change`, async ({
        page,
        context,
    }) => {
        const requests: string[] = [];
        await context.route('https://styles.example/**', (route) => {
            requests.push(route.request().url());
            return route.fulfill({
                contentType: route.request().url().endsWith('.woff2') ? 'font/woff2' : 'text/css',
                body: route.request().url().endsWith('.css') ? 'p { font-weight: 700; }' : '',
                headers: { 'Access-Control-Allow-Origin': '*' },
            });
        });
        const id = 'a'.repeat(64);
        const item = { id, kind: 'html', descriptor, createdAt: 1, ubcq: 'shared' };
        await context.route('**/library-items?**', (route) =>
            route.fulfill({ json: { items: [item], nextCursor: null } }),
        );
        await context.route('**/library-item?**', (route) => route.fulfill({ json: item }));
        await context.route('**/html-svg?**', (route) =>
            route.fulfill({
                contentType: 'image/svg+xml',
                body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50"/>',
            }),
        );
        await context.route('**/ubcq-decode?**', (route) => route.fulfill({ json: descriptor }));
        if (source === 'decode') {
            await page.goto('/');
            await page.getByLabel('UBCQ or URL', { exact: true }).fill('shared');
            await page.getByRole('button', { name: 'Decode and edit' }).click();
        } else {
            if (source === 'admin library') {
                await context.route('**/admin/login', (route) =>
                    route.fulfill({
                        json: { accessToken: 'test-token', expiresIn: 900 },
                    }),
                );
                await page.goto('/admin.html');
                await page.getByLabel('Password', { exact: true }).fill('test');
                await page.getByRole('button', { name: 'Sign in', exact: true }).click();
            } else {
                await page.goto('/#library');
            }
            await expect(page.locator('.library-preview img')).toBeVisible();
            expect(requests).toEqual([]);
            await page.getByRole('link', { name: 'Open in editor', exact: true }).click();
        }
        const preview = page.frameLocator('iframe[title="HTML live preview"]');
        await expect(page.getByLabel('Stylesheet URL 1')).toHaveValue(
            'https://styles.example/main.css',
        );
        await expect(preview.locator('.shared')).toHaveCSS('color', 'rgb(255, 0, 0)');
        await expect(preview.locator('link[rel="stylesheet"]')).toHaveCount(0);
        expect(requests).toEqual([]);
        const load = page.getByRole('button', { name: 'Load styles', exact: true });
        await expect(load).toHaveAttribute('title', /IP address and request timing/);
        await expect(
            page.getByText(
                'External stylesheets and fonts reveal your IP address to their hosts.',
                { exact: false },
            ),
        ).toBeVisible();
        await load.click();
        await expect(load).toHaveCount(0);
        await expect(preview.locator('link[rel="stylesheet"]')).toHaveAttribute(
            'href',
            descriptor.styleRefs[0],
        );
        await expect(preview.locator('.shared')).toHaveCSS('font-weight', '700', {
            timeout: 15000,
        });
        await expect.poll(() => requests.some((url) => url.endsWith('/import.css'))).toBe(true);
        await expect.poll(() => requests.some((url) => url.endsWith('/font.woff2'))).toBe(true);
        await expect(page.getByText('External styles paused', { exact: true })).toHaveCount(0);

        await page.getByLabel('UBCQ or URL', { exact: true }).fill('another');
        await page.getByRole('button', { name: 'Decode and edit' }).click();
        await expect(preview.locator('link[rel=stylesheet]')).toHaveAttribute(
            'href',
            descriptor.styleRefs[0],
        );
        await page.screenshot({
            path: `test-results/external-styles-${source.replaceAll(' ', '-')}.png`,
        });
        await expect(load).toHaveCount(0);
        await page.getByLabel('Stylesheet URL 1').fill('https://styles.example/changed.css');
        await expect(load).toBeVisible();
        await expect(preview.locator('link[rel="stylesheet"]')).toHaveCount(0);
        expect(requests.some((url) => url.endsWith('/changed.css'))).toBe(false);
        await load.click();
        await expect(load).toHaveCount(0);
        await expect.poll(() => requests.some((url) => url.endsWith('/changed.css'))).toBe(true);
        await expect(preview.locator('.shared')).toHaveCSS('font-weight', '700');
    });
}

for (const [name, css, styleRefs] of [
    ['stylesheet reference', 'p { color: red; }', ['https://styles.example/main.css']],
    ['CSS import', '@import "https://styles.example/import.css";', []],
    [
        'remote font',
        '@font-face { font-family: remote; src: url(https://styles.example/font.woff2); } p { font-family: remote; }',
        [],
    ],
] as const) {
    test(`Load styles remains available with only a ${name}`, async ({ page, context }) => {
        await context.route('https://styles.example/**', (route) =>
            route.fulfill({
                contentType: route.request().url().endsWith('.css') ? 'text/css' : 'font/woff2',
                body: '',
                headers: { 'Access-Control-Allow-Origin': '*' },
            }),
        );
        await page.route('**/ubcq-decode?**', (route) =>
            route.fulfill({
                json: {
                    forumla: '<p>Remote styling</p>',
                    style: css,
                    styleRefs,
                    imageWidth: 500,
                },
            }),
        );
        await page.goto('/');
        await expect(page.getByRole('button', { name: 'Load styles', exact: true })).toHaveCount(0);
        await page.getByLabel('UBCQ or URL', { exact: true }).fill('shared');
        await page.getByRole('button', { name: 'Decode and edit', exact: true }).click();
        const button = page.getByRole('button', { name: 'Load styles', exact: true });
        await expect(button).toBeVisible();
        const frame = page.locator('iframe[title="HTML live preview"]');
        await expect(
            page
                .frameLocator('iframe[title="HTML live preview"]')
                .getByText('Remote styling', { exact: true }),
        ).toHaveText('Remote styling');
        const previous = await frame.getAttribute('srcdoc');
        await button.click();
        await expect(frame).not.toHaveAttribute('srcdoc', previous!);
        await expect(button).toHaveCount(0);
    });
}
