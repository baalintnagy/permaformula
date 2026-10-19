import { gzipSync } from 'node:zlib';
import { expect, test } from '@playwright/test';

import { libraryPage } from './library-fixtures';

test('previews serialize requests, retry busy responses and offer recovery', async ({ page }) => {
    await page.route('**/library-items?**', (route) =>
        route.fulfill({
            json: {
                items: ['a', 'b', 'c'].map((letter) => ({
                    id: letter.repeat(64),
                    ubcq: letter.repeat(64),
                    kind: 'html',
                    descriptor: { forumla: '<p>test</p>' },
                    createdAt: 1,
                })),
                nextCursor: null,
            },
        }),
    );
    let active = 0;
    let maximum = 0;
    const attempts = new Map<string, number>();
    let recovered = false;
    await page.route('**/*-svg?**', async (route) => {
        const id = new URL(route.request().url()).searchParams.get('ubcq')!;
        const count = (attempts.get(id) ?? 0) + 1;
        attempts.set(id, count);
        maximum = Math.max(maximum, ++active);
        await new Promise((resolve) => setTimeout(resolve, 40));
        active--;
        if ((id.startsWith('a') && count === 1) || (id.startsWith('c') && !recovered)) {
            await route.fulfill({ status: 503, headers: { 'Retry-After': '0' } });
        } else {
            await route.fulfill({
                contentType: 'image/svg+xml',
                body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="40"/>',
            });
        }
    });
    await page.goto('/#library');
    const cards = page.locator('.library-item');
    await expect(cards.first().locator('img')).toBeVisible();
    await cards.nth(1).scrollIntoViewIfNeeded();
    await expect(cards.nth(1).locator('img')).toBeVisible();
    await cards.last().scrollIntoViewIfNeeded();
    await expect(cards.last().getByRole('button', { name: 'Retry preview' })).toBeVisible();
    expect(attempts.get('a'.repeat(64))).toBe(2);
    expect(attempts.get('c'.repeat(64))).toBe(4);
    recovered = true;
    await cards.last().getByRole('button', { name: 'Retry preview' }).click();
    await expect(cards.last().locator('img')).toBeVisible();
    expect(maximum).toBe(1);
    expect(
        await cards
            .last()
            .locator('img')
            .evaluate((image: HTMLImageElement) => image.naturalWidth),
    ).toBe(100);
});

test('small pages show all complete sources and CSS without additional source requests', async ({
    page,
}) => {
    const requests: string[] = [];
    await page.route('**/library-items?**', (route) =>
        route.fulfill({
            json: {
                items: ['a', 'b'].map((letter) => ({
                    id: letter.repeat(64),
                    ubcq: letter.repeat(64),
                    kind: 'html',
                    excerpt: 'short',
                    descriptor: {
                        forumla: `<p>complete source ${letter}</p>`,
                        style: `p { color: ${letter === 'a' ? 'red' : 'blue'}; }`,
                        imageWidth: 700,
                    },
                    createdAt: 1,
                })),
                nextCursor: null,
            },
        }),
    );
    await page.route('**/library-item?**', (route) => {
        requests.push(route.request().url());
        return route.fulfill({ status: 500 });
    });
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg"/>',
        }),
    );
    await page.goto('/#library');
    const cards = page.locator('.library-item');
    await expect(cards).toHaveCount(2);
    await expect(cards.first()).toContainText('complete source a');
    await expect(cards.last()).toContainText('complete source b');
    await expect(page.getByRole('button', { name: 'Show full source' })).toHaveCount(0);
    for (const card of await cards.all()) {
        await expect(card.getByText('CSS and layout', { exact: true })).toHaveCount(0);
        await expect(card.getByLabel('CSS source', { exact: true })).toBeVisible();
        await expect(card).toContainText('Canvas: 700px');
        await expect(card.getByLabel('CSS source', { exact: true })).toContainText('color:');
    }
    expect(requests).toEqual([]);
    for (const width of [1280, 600]) {
        await page.setViewportSize({ width, height: 900 });
        for (const card of await cards.all()) {
            const source = card.locator('.library-source');
            await expect(source.getByRole('slider')).toHaveCount(1);
            const zoom = source.getByRole('slider', {
                name: 'HTML and CSS source zoom',
                exact: true,
            });
            await zoom.fill('200');
            await expect(card.getByLabel('HTML source', { exact: true })).toHaveCSS(
                'font-size',
                '26px',
            );
            await expect(card.getByLabel('CSS source', { exact: true })).toHaveCSS(
                'font-size',
                '26px',
            );
            await expect(card.getByRole('slider', { name: 'Library SVG zoom' })).toHaveValue('100');
            await zoom.fill('100');
        }
    }
});

test('browsing uses excerpts and loads only one full source on explicit request', async ({
    page,
}) => {
    const first = 'd'.repeat(64);
    const second = 'e'.repeat(64);
    const original = '<p>full source beyond the excerpt</p>';
    const requested: string[] = [];
    let available = false;
    await page.route('**/library-items?**', (route) =>
        route.fulfill({
            json: {
                items: [
                    {
                        id: first,
                        ubcq: first,
                        kind: 'html',
                        excerpt: '<p>short excerpt',
                        createdAt: 1,
                    },
                    {
                        id: second,
                        ubcq: gzipSync(JSON.stringify({ forumla: 'x' })).toString('base64'),
                        kind: 'formula',
                        excerpt: null,
                        createdAt: 1,
                    },
                ],
                nextCursor: null,
            },
        }),
    );
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg"/>',
        }),
    );
    await page.route('**/library-item?**', (route) => {
        const id = new URL(route.request().url()).searchParams.get('id')!;
        requested.push(id);
        if (id === second && !available) {
            return route.fulfill({ status: 404, json: { detail: 'Image expired or unavailable' } });
        }
        return route.fulfill({
            json: {
                id,
                kind: id === first ? 'html' : 'formula',
                descriptor: { forumla: original, style: 'p { color: red; }' },
                createdAt: 1,
            },
        });
    });
    await page.goto('/#library');
    const cards = page.locator('.library-item');
    await expect(cards).toHaveCount(2);
    await expect(cards.first()).toContainText('short excerpt');
    await expect(cards.last()).toContainText('Source available on request.');
    expect(requested).toEqual([]);
    await cards.first().getByRole('button', { name: 'Show full source' }).click();
    await expect(cards.first()).toContainText('full source beyond the excerpt');
    expect(requested).toEqual([first]);
    await cards.last().getByRole('button', { name: 'Show full source' }).click();
    await expect(cards.last().getByRole('alert')).toContainText('Image expired or unavailable');
    await expect(cards.first().getByRole('button', { name: 'Show full source' })).toBeVisible();
    await expect(cards.first()).not.toContainText('full source beyond the excerpt');
    available = true;
    await cards.last().getByRole('button', { name: 'Retry source' }).click();
    await expect(cards.last()).toContainText('full source beyond the excerpt');
    expect(requested).toEqual([first, second, second]);
});

test('library follows returned cursors and keeps previous-page navigation', async ({ page }) => {
    const cursor = '123.' + 'a'.repeat(64);
    const requested: string[] = [];
    await page.route('**/library-items?**', (route) => {
        const url = new URL(route.request().url());
        expect(url.searchParams.has('offset')).toBe(false);
        requested.push(url.searchParams.get('cursor') || '');
        const next = url.searchParams.get('cursor') === cursor;
        return route.fulfill({
            json: {
                items: [
                    {
                        id: (next ? 'b' : 'a').repeat(64),
                        ubcq: (next ? 'b' : 'a').repeat(64),
                        kind: 'formula',
                        excerpt: next ? 'older' : 'newer',
                        createdAt: 123,
                    },
                ],
                nextCursor: next ? null : cursor,
            },
        });
    });
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg"/>',
        }),
    );
    await page.goto('/#library');
    await expect(page.getByRole('button', { name: 'Previous', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: 'Next', exact: true }).click();
    await expect(page.locator('.library-pagination')).toContainText('Page 2');
    await expect(page.locator('.library-source')).toContainText('older');
    await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: 'Previous', exact: true }).click();
    await expect(page.locator('.library-source')).toContainText('newer');
    // React StrictMode may abort and repeat the initial effect in development.
    expect(
        requested.filter((value, index) => index === 0 || value !== requested[index - 1]),
    ).toEqual(['', cursor, '']);
});
test('library opens the matching editor with original sources', async ({ page }) => {
    const id = 'a'.repeat(64);
    const item = {
        id,
        ubcq: id,
        kind: 'html',
        descriptor: {
            forumla: '<p>`x^2`</p>',
            style: 'p { color: red; }',
            styleRefs: [],
            imageWidth: 700,
        },
        createdAt: 1,
    };
    await page.route('**/library-items?**', (route) =>
        route.fulfill({ json: libraryPage([item]) }),
    );
    await page.route('**/library-item?**', (route) => route.fulfill({ json: item }));
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"/>',
        }),
    );
    await page.goto('/#library');
    await expect(page.getByRole('heading', { name: 'Rendered library' })).toBeVisible();
    await page.getByRole('link', { name: 'Open in editor', exact: true }).click();
    await expect(page.getByRole('textbox', { name: 'HTML source', exact: true })).toContainText(
        '<p>`x^2`</p>',
    );
    await expect(page.getByRole('spinbutton')).toHaveValue('700');
    await page.getByRole('tab', { name: /CSS/ }).click();
    await expect(page.getByRole('textbox', { name: 'CSS source', exact: true })).toContainText(
        'p { color: red; }',
    );
});
test('formula library entry restores simple expression editor', async ({ page }) => {
    const id = 'b'.repeat(64);
    await page.route('**/library-item?**', (route) =>
        route.fulfill({
            json: {
                id,
                ubcq: id,
                kind: 'formula',
                descriptor: { forumla: 'sqrt(x^2+y^2)' },
                createdAt: 1,
            },
        }),
    );
    await page.goto(`/?libraryItem=${id}#formula`);
    await expect(
        page.getByRole('textbox', { name: 'AsciiMath formula', exact: true }),
    ).toContainText('sqrt(x^2+y^2)');
});
test('empty library offers the composer', async ({ page }) => {
    await page.route('**/library-items?**', (route) => route.fulfill({ json: libraryPage([]) }));
    await page.goto('/#library');
    await expect(page.getByText('No images here yet.', { exact: false })).toBeVisible();
    await page.getByRole('link', { name: 'Create a rendered image' }).click();
    await expect(
        page.getByRole('heading', { name: 'Formula + HTML + CSS', exact: true }),
    ).toBeVisible();
});
test('library viewers zoom independently without opening the editor', async ({ page }) => {
    await page.route('**/library-items?**', (route) =>
        route.fulfill({
            json: libraryPage(
                ['a', 'b'].map((letter) => ({
                    id: letter.repeat(64),
                    ubcq: letter.repeat(64),
                    kind: 'formula',
                    descriptor: { forumla: 'x^2' },
                    createdAt: 1,
                })),
            ),
        }),
    );
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="200"><text x="10" y="30">x²</text></svg>',
        }),
    );
    await page.goto('/#library');
    const viewers = page.locator('.library-viewer');
    const image = viewers.first().locator('img');
    await expect(image).toHaveJSProperty('complete', true);
    await expect
        .poll(async () => image.evaluate((element) => element.getBoundingClientRect().width))
        .toBeLessThan(1000);
    const initialWidth = await image.evaluate((element) => element.getBoundingClientRect().width);
    expect(
        await viewers
            .first()
            .locator('.library-canvas')
            .evaluate((element) => {
                const canvas = element.getBoundingClientRect();
                const image = element.querySelector('img')!.getBoundingClientRect();
                return Math.abs(image.top + image.height / 2 - canvas.top - canvas.height / 2);
            }),
    ).toBeLessThan(1);
    const card = page.locator('.library-item').first();
    const cardSize = await card.evaluate((element) => {
        const { width, height } = element.getBoundingClientRect();
        return { width, height };
    });
    const slider = viewers.first().getByRole('slider', { name: 'Library SVG zoom' });
    await slider.fill('250');
    await expect(viewers.first().locator('output')).toHaveText('250%');
    await expect(viewers.nth(1).getByRole('slider')).toHaveValue('100');
    await expect
        .poll(async () => image.evaluate((element) => element.getBoundingClientRect().width))
        .toBeCloseTo(initialWidth * 2.5, 0);
    await expect
        .poll(async () =>
            viewers
                .first()
                .locator('.library-canvas')
                .evaluate((element) => element.scrollWidth > element.clientWidth),
        )
        .toBe(true);
    await expect(page).toHaveURL(/#library$/);
    await slider.focus();
    await page.keyboard.press('ArrowLeft');
    await expect(slider).toHaveValue('240');
    for (const zoom of ['1000', '10']) {
        await slider.fill(zoom);
        await expect
            .poll(() =>
                card.evaluate((element) => {
                    const { width, height } = element.getBoundingClientRect();
                    return { width, height };
                }),
            )
            .toEqual(cardSize);
    }
});
test('library syntax colors match Builder HTML and CSS in both themes', async ({ page }) => {
    const id = 'c'.repeat(64);
    const item = {
        id,
        kind: 'html',
        descriptor: {
            forumla: '<!-- note --><p class="card">text</p>',
            style: '/* note */ p { color: red; width: 20px; }',
            imageWidth: 500,
        },
        createdAt: 1,
    };
    await page.route('**/library-items?**', (route) =>
        route.fulfill({ json: libraryPage([item]) }),
    );
    await page.route('**/library-item?**', (route) => route.fulfill({ json: item }));
    await page.route('**/*-svg?**', (route) =>
        route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"/>',
        }),
    );
    for (const theme of ['light', 'dark']) {
        await page.goto('/#library');
        if ((await page.locator('html').getAttribute('data-theme')) !== theme) {
            await page.getByRole('button', { name: `Switch to ${theme} theme` }).click();
        }
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await expect(page.locator('.library-canvas')).toHaveCSS(
            'background-color',
            theme === 'dark' ? 'rgb(20, 21, 26)' : 'rgb(255, 255, 255)',
        );
        await page.getByRole('button', { name: 'Show full source', exact: true }).click();
        await expect(page.getByLabel('CSS source', { exact: true })).toBeVisible();
        const colors = async (selector: string) =>
            page
                .locator(selector)
                .evaluate((element) =>
                    Object.fromEntries(
                        Array.from(element.querySelectorAll('span[class*="source-"]')).map(
                            (token) => [token.textContent, getComputedStyle(token).color],
                        ),
                    ),
                );
        const plainTextColor = async (selector: string, text: string) =>
            page.locator(selector).evaluate((element, text) => {
                const walker = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
                let node;
                while ((node = walker.nextNode())) {
                    if (node.textContent?.includes(text) && node.parentElement) {
                        return getComputedStyle(node.parentElement).color;
                    }
                }
                throw new Error(`Plain text not found: ${text}`);
            }, text);
        const libraryTextColor = await plainTextColor(
            '.source-code[aria-label="HTML source"]',
            'text',
        );
        const expectedTextColor = theme === 'light' ? 'rgb(23, 33, 51)' : 'rgb(225, 231, 243)';
        expect(libraryTextColor).toBe(expectedTextColor);
        const libraryCssBaseColor = await page
            .locator('.source-code[aria-label="CSS source"] code')
            .evaluate((element) => getComputedStyle(element).color);
        expect(libraryCssBaseColor).toBe(expectedTextColor);
        const htmlColors = await colors('.library-source .source-code[aria-label="HTML source"]');
        const cssColors = await colors('.library-source .source-code[aria-label="CSS source"]');
        expect(htmlColors['p']).toBe(theme === 'light' ? 'rgb(41, 75, 116)' : 'rgb(169, 201, 240)');
        await page.getByRole('link', { name: 'Open in editor', exact: true }).click();
        await expect(page.getByRole('textbox', { name: 'HTML source', exact: true })).toContainText(
            'class="card"',
        );
        expect(await colors('.editor-html')).toEqual(htmlColors);
        expect(await plainTextColor('.editor-html', 'text')).toBe(libraryTextColor);
        await page.getByRole('tab', { name: /CSS/ }).click();
        expect(await colors('.editor-css')).toEqual(cssColors);
        expect(
            await page
                .locator('.editor-css .cm-content')
                .evaluate((element) => getComputedStyle(element).color),
        ).toBe(libraryCssBaseColor);
    }
});

test('expression syntax matches the editor in public and admin libraries', async ({ page }) => {
    const id = 'f'.repeat(64);
    const formula = 'sqrt(x^2) + pi = "area"';
    const item = { id, ubcq: id, kind: 'formula', descriptor: { forumla: formula }, createdAt: 1 };
    await page.route('**/library-items?**', (route) =>
        route.fulfill({ json: libraryPage([item]) }),
    );
    await page.route('**/library-item?**', (route) => route.fulfill({ json: item }));
    await page.route('**/admin/login', (route) =>
        route.fulfill({ json: { accessToken: 'test-token', expiresIn: 900 } }),
    );
    await page.route('**/exp-svg?**', (route) => {
        expect(route.request().headers().authorization).toBeUndefined();
        return route.fulfill({
            contentType: 'image/svg+xml',
            body: '<svg xmlns="http://www.w3.org/2000/svg" width="100" height="40"/>',
        });
    });
    const tokens = (selector: string) =>
        page.locator(selector).evaluate((element) =>
            Array.from(element.querySelectorAll('span[class*="source-"]')).map((token) => ({
                text: token.textContent,
                color: getComputedStyle(token).color,
            })),
        );
    for (const theme of ['light', 'dark']) {
        await page.goto('/?libraryItem=' + id + '#formula');
        if ((await page.locator('html').getAttribute('data-theme')) !== theme) {
            await page.getByRole('button', { name: 'Switch to ' + theme + ' theme' }).click();
        }
        await expect(
            page.getByRole('textbox', { name: 'AsciiMath formula', exact: true }),
        ).toContainText(formula);
        const expected = await tokens('.editor-asciimath');
        expect(expected.map((token) => token.text)).toEqual(
            expect.arrayContaining(['sqrt', '2', 'pi', '"area"']),
        );
        for (const path of ['/#library', '/admin.html']) {
            await page.goto(path);
            if (path === '/admin.html') {
                await page.getByLabel('Password').fill('secret');
                await page.getByRole('button', { name: 'Sign in', exact: true }).click();
            }
            const source = page.getByLabel('Expression source', { exact: true });
            await expect(source).toContainText(formula);
            expect(await tokens('.source-code[aria-label="Expression source"]')).toEqual(expected);
        }
    }
});
