import { expect, test } from '@playwright/test';
import { libraryPage } from './library-fixtures';

test('excerpt entries use stored UBCQ directly without link preparation requests', async ({
    page,
    context,
}) => {
    const ubcq = 'H4sIabc+/==';
    let extraRequests = 0;
    await context.route('**/library-items?**', (route) =>
        route.fulfill({
            json: {
                items: [{ id: 'f'.repeat(64), kind: 'html', excerpt: 'short', createdAt: 1, ubcq }],
                nextCursor: null,
            },
        }),
    );
    for (const route of ['**/library-item?**', '**/ubcq?**']) {
        await context.route(route, (request) => {
            extraRequests++;
            return request.fulfill({ status: 500 });
        });
    }
    await context.route('**/html-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="50"/>',
        }),
    );
    await context.route('**/html-jpg?**', (route) =>
        route.fulfill({ contentType: 'image/jpeg', body: Buffer.from([255, 216, 255, 217]) }),
    );
    await page.goto('/#library');
    await expect(page.locator('.library-preview img')).toBeVisible();
    for (const format of ['SVG', 'JPG']) {
        const link = page.getByRole('link', { name: `Download ${format}`, exact: true });
        const href = await link.getAttribute('href');
        expect(new URL(href!, 'http://localhost').searchParams.get('ubcq')).toBe(ubcq);
        const popup = page.waitForEvent('popup');
        await link.click();
        const tab = await popup;
        await tab.waitForURL((url) => url.pathname === `/html-${format.toLowerCase()}`);
        expect(new URL(tab.url()).searchParams.get('ubcq')).toBe(ubcq);
        await tab.close();
    }
    expect(extraRequests).toBe(0);
});

test('public and admin image links open existing endpoints in separate tabs', async ({
    page,
    context,
}) => {
    const id = 'd'.repeat(64);
    const formula = 'x^2 + y & z';
    await context.route('**/library-items?**', (route) =>
        route.fulfill({
            json: {
                items: [
                    {
                        id,
                        ubcq: id,
                        kind: 'formula',
                        descriptor: { forumla: formula },
                        excerpt: formula,
                        createdAt: 1,
                    },
                ],
                nextCursor: null,
            },
        }),
    );
    await context.route('**/admin/login', (route) =>
        route.fulfill({ json: { accessToken: 'admin-only-token', expiresIn: 900 } }),
    );
    for (const endpoint of ['exp-svg', 'exp-jpg']) {
        await context.route(`**/${endpoint}?**`, (route) => {
            expect(route.request().headers().authorization).toBeUndefined();
            return route.fulfill({
                contentType: endpoint === 'exp-svg' ? 'image/svg+xml' : 'image/jpeg',
                body:
                    endpoint === 'exp-svg'
                        ? '<svg xmlns="http://www.w3.org/2000/svg"/>'
                        : Buffer.from([0xff, 0xd8, 0xff, 0xd9]),
            });
        });
    }
    for (const path of ['/#library', '/admin.html']) {
        await page.goto(path);
        if (path === '/admin.html') {
            await page.getByLabel('Password').fill('secret');
            await page.getByRole('button', { name: 'Sign in', exact: true }).click();
        }
        await expect(page.locator('.library-item')).toHaveCount(1);
        const original = page.url();
        for (const format of ['SVG', 'JPG']) {
            const link = page.getByRole('link', { name: `Download ${format}`, exact: true });
            await expect(link).toHaveAttribute('target', '_blank');
            await expect(link).toHaveAttribute('rel', 'noopener noreferrer');
            const opened = page.waitForEvent('popup');
            await link.click();
            const tab = await opened;
            await tab.waitForURL(
                (url) => url.pathname === (format === 'SVG' ? '/exp-svg' : '/exp-jpg'),
            );
            const url = new URL(tab.url());
            expect(url.searchParams.get('formula')).toBe(formula);
            expect(url.searchParams.has('ubcq')).toBe(false);
            expect(url.searchParams.has('download')).toBe(false);
            expect(await tab.evaluate(() => window.opener === null)).toBe(true);
            await expect(page).toHaveURL(original);
            await tab.close();
        }
    }
});

test('admin and public library render identical content and share theme preferences', async ({
    page,
}) => {
    await page.route('**/admin/login', (route) =>
        route.fulfill({
            json: { accessToken: 'signed-jwt', expiresIn: 900 },
        }),
    );
    const items = [
        {
            id: 'a'.repeat(64),
            ubcq: 'a'.repeat(64),
            kind: 'formula',
            descriptor: { forumla: 'x^2' },
            createdAt: 1,
        },
        {
            id: 'b'.repeat(64),
            ubcq: 'b'.repeat(64),
            kind: 'html',
            descriptor: {
                forumla: '<p class="card">Hello</p>',
                style: 'p { color: red; }',
                imageWidth: 500,
            },
            createdAt: 1,
        },
    ];
    await page.route('**/library-items?**', (route) => route.fulfill({ json: libraryPage(items) }));
    await page.route('**/library-item?**', (route) =>
        route.fulfill({
            json: items.find(
                (item) => item.id === new URL(route.request().url()).searchParams.get('id'),
            ),
        }),
    );
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"/>',
        }),
    );
    const appearance = async () => {
        await expect(page.locator('.library-item')).toHaveCount(2);
        await expect(page.locator('.library-pagination > span')).toHaveCSS('font-size', '11px');
        await expect(page.locator('.library-pagination > span')).toHaveCSS('font-weight', '500');
        await expect(page.locator('.library-item img').first()).toHaveCSS('width', '100px');
        await expect(page.locator('.library-item img').last()).toHaveCSS('width', '100px');
        await expect(page.locator('.library-card-footer').first()).toContainText(
            'Simple expression',
        );
        await expect(page.locator('.library-card-footer').last()).toContainText('HTML composition');
        for (const label of ['Expression source', 'HTML source']) {
            const slider = page.getByRole('slider', {
                name: label === 'HTML source' ? 'HTML and CSS source zoom' : `${label} zoom`,
                exact: true,
            });
            const source = page.getByLabel(label, { exact: true });
            await slider.fill('200');
            await expect(source).toHaveCSS('font-size', '26px');
            await expect(source).toHaveCSS('height', '220px');
            await slider.fill('100');
        }
        await page
            .locator('.library-item')
            .last()
            .getByRole('button', { name: 'Show full source', exact: true })
            .click();
        await expect(page.getByLabel('CSS source', { exact: true })).toBeVisible();
        const cssZoom = page.getByRole('slider', { name: 'HTML and CSS source zoom', exact: true });
        await cssZoom.fill('200');
        await expect(page.getByLabel('CSS source', { exact: true })).toHaveCSS('font-size', '26px');
        await expect(page.getByLabel('HTML source', { exact: true })).toHaveCSS(
            'font-size',
            '26px',
        );
        await cssZoom.fill('100');
        await expect(page.locator('.library-preview img')).toHaveCount(2);
        await expect
            .poll(() =>
                page
                    .locator('.library-preview img')
                    .evaluateAll((images) =>
                        images.every((image) => (image as HTMLImageElement).naturalWidth > 0),
                    ),
            )
            .toBe(true);
        return page.locator('main').evaluate((main) => {
            const clone = main.cloneNode(true) as HTMLElement;
            clone.querySelectorAll('.delete-button').forEach((button) => button.remove());
            // Object URLs identify individual fetches, not differences in rendered content.
            clone.querySelectorAll('img').forEach((image) => image.setAttribute('src', 'preview'));
            return {
                content: clone.innerHTML,
                styles: Array.from(
                    main.querySelectorAll(
                        '.library-canvas, .source-code, .source-code span, .panel-heading',
                    ),
                ).map((element) => {
                    const style = getComputedStyle(element);
                    return [
                        style.color,
                        style.backgroundColor,
                        style.fontFamily,
                        style.fontSize,
                        element.getBoundingClientRect().width,
                    ];
                }),
            };
        });
    };
    for (const theme of ['light', 'dark']) {
        await page.goto('/#library');
        if ((await page.locator('html').getAttribute('data-theme')) !== theme) {
            await page.getByRole('button', { name: `Switch to ${theme} theme` }).click();
        }
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        const publicAppearance = await appearance();
        await page.goto('/admin.html');
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await page.getByLabel('Password').fill('secret');
        await page.getByRole('button', { name: 'Sign in', exact: true }).click();
        expect(await appearance()).toEqual(publicAppearance);
        await expect(page.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(2);
        const otherTheme = theme === 'light' ? 'dark' : 'light';
        await page.getByRole('button', { name: `Switch to ${otherTheme} theme` }).click();
        await expect(page.locator('html')).toHaveAttribute('data-theme', otherTheme);
        expect(await page.evaluate(() => localStorage.getItem('permaformula-theme-choice'))).toBe(
            otherTheme,
        );
    }
});

test('failed login and expired JWT return to the login form', async ({ page }) => {
    let valid = false;
    await page.route('**/admin/login', (route) =>
        valid
            ? route.fulfill({
                  json: { accessToken: 'expired-on-server', tokenType: 'Bearer', expiresIn: 900 },
              })
            : route.fulfill({ status: 401, json: { detail: 'Invalid username or password' } }),
    );
    await page.route('**/library-items?**', (route) =>
        route.fulfill({
            json: libraryPage([
                {
                    id: 'a'.repeat(64),
                    ubcq: 'a'.repeat(64),
                    kind: 'formula',
                    descriptor: { forumla: 'x' },
                    createdAt: 1,
                },
            ]),
        }),
    );
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg"/>',
        }),
    );
    await page.route('**/admin/cache-item?**', (route) => route.fulfill({ status: 401 }));
    await page.goto('/admin.html');
    await page.getByLabel('Password').fill('wrong');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Invalid username or password');
    valid = true;
    await page.getByLabel('Password').fill('secret');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Admin login' })).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('Your login has expired');
    await expect(page.getByLabel('Password')).toHaveValue('');
});

test('separate admin page deletes and refreshes the library', async ({ page }) => {
    const id = 'a'.repeat(64);
    let deleted = false;
    await page.route('**/admin/login', (route) => {
        expect(route.request().postDataJSON()).toEqual({ username: 'admin', password: 'secret' });
        return route.fulfill({
            json: { accessToken: 'signed-jwt', tokenType: 'Bearer', expiresIn: 900 },
        });
    });
    await page.route('**/library-items?**', (route) =>
        route.fulfill({
            json: libraryPage(
                deleted
                    ? []
                    : [
                          {
                              id,
                              ubcq: id,
                              kind: 'formula',
                              descriptor: { forumla: 'x^2' },
                              createdAt: 1,
                          },
                      ],
            ),
        }),
    );
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg"/>',
        }),
    );
    await page.route('**/admin/cache-item?**', (route) => {
        expect(route.request().method()).toBe('DELETE');
        expect(route.request().headers().authorization).toBe('Bearer signed-jwt');
        deleted = true;
        return route.fulfill({ status: 204 });
    });
    await page.goto('/admin.html');
    await expect(page.getByRole('heading', { name: 'Admin login' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);
    await page.getByLabel('Password').fill('secret');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Rendered library' })).toBeVisible();
    page.once('dialog', (dialog) => dialog.dismiss());
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    expect(deleted).toBe(false);
    page.once('dialog', (dialog) => dialog.accept());
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    await expect(page.getByText('No images here yet.', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('heading', { name: 'Admin login' })).toBeVisible();
    await page.goto('/#library');
    await expect(page.getByRole('button', { name: 'Delete', exact: true })).toHaveCount(0);
});
