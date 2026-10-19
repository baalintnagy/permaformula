import { expect, test } from '@playwright/test';

for (const admin of [false, true]) {
    test(`${admin ? 'admin' : 'public'} library restores saved links and SVG without Render`, async ({
        page,
        context,
    }) => {
        const id = 'c'.repeat(64);
        const ubcq = 'H4sI+a/b+/==';
        const item = {
            id,
            ubcq,
            kind: 'html',
            createdAt: 1,
            descriptor: {
                forumla: '<p>Saved content</p>',
                style: 'p { color: red; }',
                imageWidth: 700,
            },
        };
        let preparations = 0;
        await context.route('**/ubcq?**', (route) => {
            preparations++;
            return route.fulfill({ body: ubcq });
        });
        await context.route('**/library-items?**', (route) =>
            route.fulfill({ json: { items: [item], nextCursor: null } }),
        );
        await context.route('**/library-item?**', (route) => route.fulfill({ json: item }));
        const images: { authorization?: string; cookie?: string }[] = [];
        await context.route('**/html-svg?**', (route) => {
            images.push(route.request().headers());
            return route.fulfill({
                contentType: 'image/svg+xml',
                body: '<svg xmlns="http://www.w3.org/2000/svg" width="700" height="120"><text>Saved render</text></svg>',
            });
        });
        if (admin) {
            await context.route('**/admin/login', (route) =>
                route.fulfill({ json: { accessToken: 'admin-token', expiresIn: 900 } }),
            );
            await page.goto('/admin.html');
            await page.getByLabel('Password', { exact: true }).fill('test');
            await page.getByRole('button', { name: 'Sign in', exact: true }).click();
        } else {
            await page.goto('/#library');
        }
        await expect(page.locator('.library-preview img')).toBeVisible();
        await page.getByRole('link', { name: 'Open in editor', exact: true }).click();
        await expect(page.getByRole('textbox', { name: 'HTML source', exact: true })).toContainText(
            'Saved content',
        );
        await expect(page.getByLabel('Canvas width')).toHaveValue('700');
        const svg = await page.getByLabel('Renered SVG URL', { exact: true }).inputValue();
        expect(new URL(svg).searchParams.get('ubcq')).toBe(ubcq);
        await expect(page.getByLabel('Renered JPG URL', { exact: true })).toHaveValue(
            svg.replace('/html-svg?', '/html-jpg?'),
        );
        await expect(page.frameLocator('iframe[title="Renered SVG"]').locator('text')).toHaveText(
            'Saved render',
        );
        await expect(page.getByText('Your SVG is ready.', { exact: true })).toBeVisible();
        expect(preparations).toBe(0);
        expect(images.length).toBeGreaterThan(1);
        expect(images.every((headers) => !headers.authorization && !headers.cookie)).toBe(true);
        await page.getByLabel('Canvas width').fill('800');
        await expect(
            page.getByLabel('Renered SVG URL (previous input)', { exact: true }),
        ).toHaveValue(svg);
    });
}

test('restored links remain available when the saved SVG cannot be loaded', async ({ page }) => {
    await page.route('**/library-item?**', (route) =>
        route.fulfill({
            json: {
                id: 'd'.repeat(64),
                ubcq: 'H4sI+a/b+/==',
                kind: 'html',
                createdAt: 1,
                descriptor: { forumla: '<p>Saved content</p>', imageWidth: 500 },
            },
        }),
    );
    await page.route('**/html-svg?**', (route) => route.fulfill({ status: 503 }));
    await page.goto(`/?libraryItem=${'d'.repeat(64)}#composer`);
    await expect(page.getByLabel('Renered SVG URL', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Renered JPG URL', { exact: true })).toBeVisible();
    await expect(page.locator('#composer [role="alert"]')).toContainText('503');
    await expect(
        page.locator('#composer').getByRole('button', { name: 'Render', exact: true }),
    ).toBeEnabled();
});
