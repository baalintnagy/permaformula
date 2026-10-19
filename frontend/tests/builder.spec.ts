import { expect, test } from '@playwright/test';

const svg =
    '<svg xmlns="http://www.w3.org/2000/svg" width="720" height="1400" viewBox="0 0 720 1400"><text x="10" y="30">Rendered SVG</text></svg>';
const ubcq = 'H4sI+a/b+/==';
const permalink = 'http://localhost:8086/html-svg?ubcq=' + encodeURIComponent(ubcq);

test('each source editor has an independent top zoom control', async ({ page }) => {
    await page.goto('/');
    const card = page.locator('.expression-editor-panel');
    const height = await card.evaluate((element) => element.getBoundingClientRect().height);
    await page.getByRole('slider', { name: 'AsciiMath formula zoom', exact: true }).fill('200');
    await expect(card.locator('.cm-editor')).toHaveCSS('font-size', '26px');
    expect(await card.evaluate((element) => element.getBoundingClientRect().height)).toBe(height);
    await page.getByRole('slider', { name: 'HTML source zoom', exact: true }).fill('200');
    await expect(page.locator('.editor-html .cm-editor')).toHaveCSS('font-size', '26px');
    await page.getByRole('tab', { name: /CSS/ }).click();
    await expect(page.getByRole('slider', { name: 'CSS source zoom', exact: true })).toHaveValue(
        '100',
    );
    await page.getByRole('slider', { name: 'CSS source zoom', exact: true }).fill('150');
    await expect(page.locator('.editor-css .cm-editor')).toHaveCSS('font-size', '19.5px');
    await page.getByRole('tab', { name: /HTML/ }).click();
    await expect(page.getByRole('slider', { name: 'HTML source zoom', exact: true })).toHaveValue(
        '200',
    );
});

test('expression editor scrolls many lines without growing its card', async ({ page }) => {
    await page.goto('/');
    const card = page.locator('.expression-editor-panel');
    const size = () =>
        card.evaluate((element) => {
            const { width, height } = element.getBoundingClientRect();
            return { width, height };
        });
    const original = await size();
    await expect(card.locator('.cm-editor')).toHaveCSS('position', 'absolute');
    await page
        .getByRole('textbox', { name: 'AsciiMath formula', exact: true })
        .fill(Array.from({ length: 100 }, (_, i) => `x_${i}`).join('\n'));
    await expect.poll(size).toEqual(original);
    const scroller = card.locator('.cm-scroller');
    await expect
        .poll(() => scroller.evaluate((element) => element.scrollHeight > element.clientHeight))
        .toBe(true);
    await scroller.evaluate((element) => {
        element.scrollTop = element.scrollHeight;
    });
    expect(await scroller.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
});

test('expression live preview keeps scaling beyond the iframe width', async ({ page }) => {
    await page.goto('/');
    const frame = page.frameLocator('iframe[title="Formula live preview"]');
    const svg = frame.locator('svg');
    await expect(svg).toBeVisible();
    expect(
        await svg.evaluate((element) => {
            const bounds = element.getBoundingClientRect();
            return Math.abs(bounds.top + bounds.height / 2 - window.innerHeight / 2);
        }),
    ).toBeLessThan(1);
    const initialWidth = await svg.evaluate((element) => element.getBoundingClientRect().width);
    const zoom = page.getByRole('slider', { name: 'Formula preview zoom' });
    for (const percent of [400, 1000]) {
        await zoom.fill(String(percent));
        await expect
            .poll(() => svg.evaluate((element) => element.getBoundingClientRect().width))
            .toBeCloseTo((initialWidth * percent) / 100, 0);
    }
    expect(
        await frame
            .locator('html')
            .evaluate((element) => element.scrollWidth > element.clientWidth),
    ).toBe(true);
    expect(
        await svg.evaluate((element) => element.getBoundingClientRect().left),
    ).toBeGreaterThanOrEqual(0);
});

test('composition result keeps top zoom controls and expression has only live preview', async ({
    page,
}) => {
    await page.route('**/exp-svg?**', (route) =>
        route.fulfill({ contentType: 'image/svg+xml', body: svg }),
    );
    await page.route('**/html-svg?**', (route) =>
        route.fulfill({ contentType: 'image/svg+xml', body: svg }),
    );
    await page.route('**/ubcq?**', (route) =>
        route.fulfill({ contentType: 'text/plain', body: ubcq }),
    );
    await page.goto('/');
    await expect(
        page.locator('#formula').getByRole('button', { name: 'Render', exact: true }),
    ).toHaveCount(0);
    await expect(page.locator('.server-result')).toHaveCount(0);
    await page.locator('#composer').getByRole('button', { name: 'Render', exact: true }).click();
    const composition = page.locator('.generated-viewer');
    await expect(composition.locator('iframe')).toBeVisible();
    const compositionZoom = composition.getByRole('slider', { name: 'Rendered SVG zoom' });
    const cardSize = await composition.evaluate((element) => {
        const { width, height } = element.getBoundingClientRect();
        return { width, height };
    });
    for (const percent of ['10', '1000']) {
        await compositionZoom.fill(percent);
        await expect
            .poll(() =>
                composition.evaluate((element) => {
                    const { width, height } = element.getBoundingClientRect();
                    return { width, height };
                }),
            )
            .toEqual(cardSize);
    }
    await compositionZoom.fill('1000');
    await expect(composition.locator('iframe')).toHaveCSS(
        'transform',
        'matrix(10, 0, 0, 10, 0, 0)',
    );
    for (const result of [composition]) {
        const positions = await result.evaluate((element) => ({
            controlBottom: element.querySelector('.zoom-control')!.getBoundingClientRect().bottom,
            canvasTop: element
                .querySelector('.result-canvas, .generated-scroll')!
                .getBoundingClientRect().top,
        }));
        expect(positions.controlBottom).toBeLessThanOrEqual(positions.canvasTop);
        await expect(result.locator('.zoom-control')).toHaveCSS('font-size', '11px');
    }
});

test('color picker applies on double-click and outside click', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('tab', { name: /CSS/ }).click();
    const editor = page.getByRole('textbox', { name: 'CSS source', exact: true });
    await page.getByLabel('Change CSS color #edf3fb', { exact: true }).click();
    await page.getByLabel('Selected color', { exact: true }).fill('#112233');
    await expect(editor).toContainText('#edf3fb');
    await page.getByLabel('Selected color', { exact: true }).dblclick();
    await expect(editor).toContainText('#112233');
    await expect(page.getByRole('dialog', { name: 'CSS color picker' })).toHaveCount(0);
    await page.getByLabel('Change CSS color #112233', { exact: true }).click();
    await page.getByLabel('Selected color', { exact: true }).fill('#445566');
    await page.getByRole('heading', { name: 'Formula + HTML + CSS' }).click();
    await expect(editor).toContainText('#445566');
    await page.getByLabel('Change CSS color #445566', { exact: true }).click();
    await page
        .getByRole('dialog', { name: 'CSS color picker' })
        .locator('canvas')
        .dblclick({ position: { x: 100, y: 70 } });
    await expect(page.getByRole('dialog', { name: 'CSS color picker' })).toHaveCount(0);
    await expect(editor).not.toContainText('#445566');
});

test('wrapping links, formula zoom and CSS color edits', async ({ page }) => {
    await page.goto('/');
    const formulaFrame = page.frameLocator('iframe[title="Formula live preview"]');
    await expect(formulaFrame.locator('svg')).toBeVisible();
    for (const percent of [10, 1000]) {
        await page.getByRole('slider', { name: 'Formula preview zoom' }).fill(String(percent));
        await expect
            .poll(() =>
                formulaFrame
                    .locator('#formula-preview')
                    .evaluate((element) => (element as HTMLElement).style.fontSize),
            )
            .toBe(`${percent}%`);
        await page.getByRole('slider', { name: 'HTML preview zoom' }).fill(String(percent));
        await expect(page.locator('iframe[title="HTML live preview"]')).toHaveCSS(
            'zoom',
            String(percent / 100),
        );
    }
    await page.getByRole('slider', { name: 'Formula preview zoom' }).fill('200');
    await expect(formulaFrame.locator('#formula-preview')).toHaveCSS('font-size', '32px');
    const htmlPreview = page.locator('iframe[title="HTML live preview"]');
    const canvasWidth = page.getByRole('spinbutton', { name: 'Canvas width' });
    const originalWidth = await canvasWidth.inputValue();
    await page.getByRole('slider', { name: 'HTML preview zoom' }).fill('150');
    await expect(htmlPreview).toHaveCSS('zoom', '1.5');
    await expect(canvasWidth).toHaveValue(originalWidth);
    await page.getByRole('tab', { name: /CSS/ }).click();
    const picker = page.getByLabel('Change CSS color #edf3fb', { exact: true });
    await expect(picker).toBeVisible();
    await picker.fill('#123456');
    await expect(page.getByRole('textbox', { name: 'CSS source', exact: true })).toContainText(
        '#123456',
    );
    await expect(
        page.frameLocator('iframe[title="HTML live preview"]').locator('.math-card'),
    ).toHaveCSS('background-color', 'rgb(18, 52, 86)');
    await expect(htmlPreview).toHaveCSS('zoom', '1.5');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('slider', { name: 'HTML preview zoom' }).fill('50');
    await expect(htmlPreview).toHaveCSS('zoom', '0.5');
    await page
        .getByRole('textbox', { name: 'AsciiMath formula', exact: true })
        .fill('x+'.repeat(80) + '1');
    const url = page.getByLabel('Expression SVG URL', { exact: true });
    await expect.poll(() => url.evaluate((field) => field.clientHeight)).toBeGreaterThan(40);
    expect(await url.evaluate((field) => field.scrollWidth <= field.clientWidth)).toBe(true);
});

test('local math previews, exact query encoding, server rendering and new-tab permalink flow', async ({
    page,
}) => {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.route('https://example.com/*.css', (route) =>
        route.fulfill({ contentType: 'text/css', body: '.caption { font-weight: 500; }' }),
    );
    const requests: string[] = [];
    page.on('request', (request) => {
        if (
            ['/exp-svg', '/html-svg', '/ubcq', '/url-encode'].includes(
                new URL(request.url()).pathname,
            )
        ) {
            requests.push(request.url());
        }
    });
    await page
        .context()
        .route('**/exp-svg?**', (route) =>
            route.fulfill({ contentType: 'image/svg+xml', body: svg }),
        );
    await page
        .context()
        .route('**/html-svg?**', (route) =>
            route.fulfill({ contentType: 'image/svg+xml', body: svg }),
        );
    await page.route('**/ubcq?**', (route) =>
        route.fulfill({ contentType: 'text/plain', body: ubcq }),
    );
    await page
        .context()
        .route('**/*-jpg?**', (route) =>
            route.fulfill({ contentType: 'image/svg+xml', body: svg }),
        );
    await page.goto('/');
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
    ).toBeVisible();
    await expect(
        page.frameLocator('iframe[title="HTML live preview"]').locator('.math-card'),
    ).toHaveCSS('background-color', 'rgb(237, 243, 251)');
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('mjx-assistive-mml'),
    ).toHaveCSS('position', 'absolute');
    await expect(
        page.frameLocator('iframe[title="HTML live preview"]').locator('svg'),
    ).toBeVisible();
    expect(requests).toHaveLength(0);

    const formula = 'sqrt(x^2 + y^2) + α & 50%';
    await page.getByRole('textbox', { name: 'AsciiMath formula', exact: true }).fill(formula);
    await expect
        .poll(async () =>
            new URL(
                await page.getByLabel('Expression SVG URL', { exact: true }).inputValue(),
            ).searchParams.get('formula'),
        )
        .toBe(formula);
    await page.getByRole('button', { name: 'Copy Expression SVG URL', exact: true }).click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
        await page.getByLabel('Expression SVG URL', { exact: true }).inputValue(),
    );
    const formulaSection = page.locator('#formula');
    const formulaLink = formulaSection.getByRole('link', { name: 'Open SVG', exact: true });
    const currentFormulaUrl = await page
        .getByLabel('Expression SVG URL', { exact: true })
        .inputValue();
    await expect(formulaLink).toBeVisible();
    await expect(formulaLink).toHaveAttribute('href', currentFormulaUrl);
    await expect(formulaLink).toHaveAttribute('target', '_blank');
    expect(requests.some((url) => new URL(url).pathname === '/exp-svg')).toBe(false);
    const formulaPopupPromise = page.waitForEvent('popup');
    await formulaLink.click();
    const formulaPopup = await formulaPopupPromise;
    await formulaPopup.waitForURL(currentFormulaUrl);
    await formulaPopup.close();
    const formulaJpgUrl = await page.getByLabel('Expression JPG URL', { exact: true }).inputValue();
    expect(new URL(formulaJpgUrl).pathname).toBe('/exp-jpg');
    expect(new URL(formulaJpgUrl).searchParams.get('formula')).toBe(formula);
    await page.getByRole('button', { name: 'Copy Expression JPG URL', exact: true }).click();
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(formulaJpgUrl);
    const formulaJpgLink = formulaSection.getByRole('link', { name: 'Open JPG', exact: true });
    await expect(formulaJpgLink).toHaveAttribute('href', formulaJpgUrl);
    await expect(formulaJpgLink).toHaveAttribute('target', '_blank');
    const formulaJpgPopupPromise = page.waitForEvent('popup');
    await formulaJpgLink.click();
    const formulaJpgPopup = await formulaJpgPopupPromise;
    await formulaJpgPopup.waitForURL(formulaJpgUrl);
    await formulaJpgPopup.close();
    await page.getByRole('button', { name: 'Add stylesheet' }).click();
    await page.getByLabel('Stylesheet URL 1').fill('https://example.com/one.css');
    await page.getByRole('button', { name: 'Add stylesheet' }).click();
    await page.getByLabel('Stylesheet URL 2').fill('https://example.com/two.css');
    await page.getByRole('button', { name: 'Move stylesheet 2 up' }).click();
    await page.getByLabel('Canvas width').fill('720');
    let popupCount = 0;
    page.on('popup', () => popupCount++);
    await page.locator('#composer').getByRole('button', { name: 'Render' }).click();
    await expect(page.frameLocator('iframe[title="Renered SVG"]').locator('svg')).toBeVisible();
    expect(popupCount).toBe(0);
    await expect(page.locator('.generated-viewer')).toHaveCSS('animation-name', 'result-ready');
    const renderedFrame = page.locator('iframe[title="Renered SVG"]');
    const originalBounds = await renderedFrame.boundingBox();
    expect(originalBounds?.width).toBe(720);
    expect(originalBounds?.height).toBe(1400);
    const svgBounds = await page
        .frameLocator('iframe[title="Renered SVG"]')
        .locator('svg')
        .boundingBox();
    expect(svgBounds?.width).toBe(720);
    expect(svgBounds?.height).toBe(1400);
    await page.getByRole('slider', { name: 'Rendered SVG zoom' }).fill('200');
    await expect(renderedFrame).toHaveCSS('transform', 'matrix(2, 0, 0, 2, 0, 0)');
    await expect(page.locator('.generated-viewer output')).toHaveText('200%');
    await expect(page.locator('iframe[title="Renered SVG"]')).toHaveCSS('width', '720px');
    const zoomedBounds = await renderedFrame.boundingBox();
    expect(zoomedBounds?.width).toBe(1440);
    const canvasBounds = await page.locator('.generated-zoom-canvas').boundingBox();
    expect(zoomedBounds?.x).toBe(canvasBounds?.x);
    expect(zoomedBounds?.width).toBe((originalBounds?.width ?? 0) * 2);
    expect(zoomedBounds?.height).toBe(2800);
    await page.getByLabel('Canvas width').fill('500');
    await expect(page.getByText('Showing the previous SVG.')).toBeVisible();
    await expect(page.getByLabel('Renered JPG URL (previous input)', { exact: true })).toHaveValue(
        permalink.replace('/html-svg?', '/html-jpg?'),
    );
    await page.getByLabel('Canvas width').fill('720');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await expect(page.locator('.generated-viewer')).toHaveCSS('animation-name', 'none');
    await page.locator('#composer').getByRole('button', { name: 'Render' }).click();
    await expect(page.frameLocator('iframe[title="Renered SVG"]').locator('svg')).toBeVisible();
    await expect(page.getByText('Your SVG is ready.', { exact: true })).toBeVisible();
    expect(popupCount).toBe(0);
    const popupPromise = page.waitForEvent('popup');
    await page.locator('#composer').getByRole('link', { name: 'Open SVG', exact: true }).click();
    const popup = await popupPromise;
    await popup.waitForURL(permalink);
    await expect(page.getByLabel('Renered SVG URL', { exact: true })).toHaveValue(permalink);
    const composer = page.locator('#composer');
    const jpgPermalink = permalink.replace('/html-svg?', '/html-jpg?');
    await expect(page.getByLabel('Renered JPG URL', { exact: true })).toHaveValue(jpgPermalink);
    for (const format of ['SVG', 'JPG']) {
        const expected = format === 'SVG' ? permalink : jpgPermalink;
        await composer
            .getByRole('button', { name: `Copy Renered ${format} URL`, exact: true })
            .click();
        expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(expected);
    }
    const jpgLink = composer.getByRole('link', { name: 'Open JPG', exact: true });
    await expect(jpgLink).toHaveAttribute('href', jpgPermalink);
    await expect(jpgLink).toHaveAttribute('target', '_blank');
    const jpgPopupPromise = page.waitForEvent('popup');
    await jpgLink.click();
    const jpgPopup = await jpgPopupPromise;
    await jpgPopup.waitForURL(jpgPermalink);
    await jpgPopup.close();
    const request = new URL(requests.find((url) => new URL(url).pathname === '/ubcq')!);
    expect(request.searchParams.get('formula')).toContain('`sum_(i=1)^n');
    expect(request.searchParams.get('style')).toContain('.math-card');
    expect(request.searchParams.get('imageWidth')).toBe('720');
    expect(request.searchParams.getAll('styleRefs')).toEqual([
        'https://example.com/two.css',
        'https://example.com/one.css',
    ]);
    expect(requests.some((url) => new URL(url).pathname === '/url-encode')).toBe(false);
});

test('theme persists, mobile layout fits, and user HTML stays isolated', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/');
    await page.getByRole('button', { name: /Switch to .* theme/ }).click();
    const theme = await page.locator('html').getAttribute('data-theme');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme!);
    const editor = page.getByRole('textbox', { name: 'HTML source', exact: true });
    await editor.fill(
        '<h1 class="user-heading">Isolated preview</h1><script>parent.document.body.dataset.compromised="yes"</script><img src=x onerror="parent.document.body.dataset.compromised=\'yes\'">',
    );
    const frame = page.frameLocator('iframe[title="HTML live preview"]');
    await expect(frame.getByRole('heading', { name: 'Isolated preview' })).toBeVisible();
    await expect(frame.locator('body script, body img')).toHaveCount(0);
    expect(await page.locator('body').getAttribute('data-compromised')).toBeNull();
    await expect(page.locator('iframe[title="HTML live preview"]')).toHaveAttribute(
        'sandbox',
        'allow-scripts',
    );
    await page.getByRole('tab', { name: 'CSS' }).click();
    await page
        .getByRole('textbox', { name: 'CSS source', exact: true })
        .fill('.user-heading { color: rgb(200, 20, 30); }');
    await expect(frame.locator('h1')).toHaveCSS('color', 'rgb(200, 20, 30)');
    expect(
        await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
    await page.screenshot({ path: 'test-results/builder-mobile.png', fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1100 });
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
    await page.screenshot({ path: 'test-results/builder-desktop.png', fullPage: true });
});

test('validation and server rendering failures are visible', async ({ page }) => {
    await page.context().route('**/exp-svg?**', (route) =>
        route.fulfill({
            status: 400,
            contentType: 'application/problem+json',
            body: JSON.stringify({ detail: 'Invalid formula input' }),
        }),
    );
    await page.route('**/ubcq?**', (route) =>
        route.fulfill({ contentType: 'text/plain', body: ubcq }),
    );
    await page.goto('/');
    await page.getByLabel('Canvas width').fill('219');
    await expect(page.locator('#composer').getByRole('button', { name: 'Render' })).toBeDisabled();
    await page.getByLabel('Canvas width').fill('500');
    await page.getByRole('button', { name: 'Add stylesheet' }).click();
    await page.getByLabel('Stylesheet URL 1').fill('http://example.com/style.css');
    await expect(page.locator('#composer').getByRole('button', { name: 'Render' })).toBeDisabled();
    await page.getByRole('button', { name: 'Remove stylesheet 1' }).click();
    await page.route('**/html-svg?**', (route) =>
        route.fulfill({
            status: 400,
            contentType: 'application/problem+json',
            body: JSON.stringify({ detail: 'Unable to render HTML' }),
        }),
    );
    await page.locator('#composer').getByRole('button', { name: 'Render' }).click();
    await expect(page.locator('#composer').getByRole('alert')).toHaveText('Unable to render HTML');
    await expect(
        page.locator('#composer').getByRole('link', { name: 'Open SVG', exact: true }),
    ).toHaveCount(0);
    await page.route('**/html-svg?**', (route) =>
        route.fulfill({ contentType: 'image/svg+xml', body: svg }),
    );
    await page.locator('#composer').getByRole('button', { name: 'Render' }).click();
    await expect(page.frameLocator('iframe[title="Renered SVG"]').locator('svg')).toBeVisible();
    await expect(
        page.locator('#composer').getByRole('link', { name: 'Open SVG', exact: true }),
    ).toHaveAttribute('href', permalink);
});

test('initial builder renders in desktop and mobile light/dark layouts', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 1100 });
    await page.goto('/');
    await expect(
        page.frameLocator('iframe[title="HTML live preview"]').locator('svg'),
    ).toBeVisible();
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
    ).toBeVisible();
    await page.screenshot({ path: 'test-results/builder-light-desktop.png', fullPage: true });
    await page.getByRole('button', { name: 'Switch to dark theme' }).click();
    await page.screenshot({ path: 'test-results/builder-dark-desktop.png', fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole('heading', { name: 'Simple expression' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
    );
    await page.screenshot({ path: 'test-results/builder-dark-mobile.png', fullPage: true });
    await page.getByRole('button', { name: 'Switch to light theme' }).click();
    await page.screenshot({ path: 'test-results/builder-light-mobile.png', fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1100 });
    await page.locator('#composer').scrollIntoViewIfNeeded();
    await page.screenshot({ path: 'test-results/builder-composer-desktop.png' });
});

test('decode restores sources, width, preview and highlights on explicit interaction', async ({
    page,
}) => {
    const html = '<p class="decoded">Decoded `x^2`</p>';
    let calls = 0;
    await page.route('**/ubcq-decode?**', (route) => {
        calls++;
        expect(new URL(route.request().url()).searchParams.get('ubcq')).toBe(ubcq);
        return route.fulfill({
            contentType: 'application/json',
            body: JSON.stringify({
                forumla: html,
                style: '.decoded { color: red; }',
                styleRefs: ['https://example.com/a.css'],
                imageWidth: 640,
            }),
        });
    });
    await page.route('https://example.com/a.css', (route) =>
        route.fulfill({ contentType: 'text/css', body: 'p { font-weight: bold; }' }),
    );
    await page.goto('/');
    await page.getByLabel('UBCQ or URL', { exact: true }).fill(permalink + '&other=1#preview');
    expect(calls).toBe(0);
    await page.getByRole('button', { name: 'Decode and edit' }).click();
    await expect(page.getByRole('textbox', { name: 'HTML source', exact: true })).toContainText(
        'Decoded',
    );
    await expect(page.getByLabel('Canvas width')).toHaveValue('640');
    await expect(page.getByLabel('Stylesheet URL 1')).toHaveValue('https://example.com/a.css');
    await expect(page.locator('.composer-editors')).toHaveClass(/result-highlight/);
    await expect(page.locator('#composer .preview-panel')).toHaveClass(/result-highlight/);
    await expect(
        page.frameLocator('iframe[title="HTML live preview"]').locator('.decoded'),
    ).toHaveCSS('color', 'rgb(255, 0, 0)');
    await page.getByRole('tab', { name: /CSS/ }).click();
    await expect(page.getByRole('textbox', { name: 'CSS source', exact: true })).toContainText(
        '.decoded',
    );
    await page.route('**/ubcq-decode?**', (route) =>
        route.fulfill({
            status: 400,
            contentType: 'application/problem+json',
            body: JSON.stringify({ detail: 'Invalid compressed payload' }),
        }),
    );
    await page.getByRole('button', { name: 'Decode and edit' }).click();
    await expect(page.getByRole('alert')).toContainText('Invalid compressed payload');
    await expect(page.getByRole('textbox', { name: 'CSS source', exact: true })).toContainText(
        '.decoded',
    );
});
