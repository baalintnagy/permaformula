import { expect, test, type Page } from '@playwright/test';

async function preparePreview(page: Page) {
    await page.route('**/ubcq?**', (route) => route.fulfill({ body: 'fixture' }));
    await page.goto('/#composer');
    const html = page.getByRole('textbox', { name: 'HTML source', exact: true });
    await html.fill(
        '<div class="scrollbox"><div class="inner">Nested scroll</div></div>\n' +
            '<div class="canvas">' +
            Array.from({ length: 80 }, (_, i) => `<p>Line ${i}</p>`).join('\n') +
            '</div>',
    );
    await page.getByRole('tab', { name: /CSS/ }).click();
    const css = page.getByRole('textbox', { name: 'CSS source', exact: true });
    await css.fill(
        '.canvas { width: 1400px; min-height: 2400px; }\n' +
            '.scrollbox { width: 220px; height: 120px; overflow: auto; }\n' +
            '.inner { width: 1000px; height: 900px; }',
    );
    await page.getByLabel('Canvas width').fill('700');
    await page.getByRole('slider', { name: 'HTML preview zoom', exact: true }).fill('150');
    const preview = page.locator('iframe[title="HTML live preview"]');
    const frame = page.frameLocator('iframe[title="HTML live preview"]');
    await expect(frame.locator('.canvas')).toHaveCSS('width', '1400px');
    await expect(preview).toHaveCSS('width', '700px');
    await frame.locator('.scrollbox').evaluate((element) => {
        element.scrollLeft = 90;
        element.scrollTop = 140;
        document.scrollingElement!.scrollLeft = 130;
        document.scrollingElement!.scrollTop = 240;
    });
    const outer = page.locator('#composer .preview-scroll');
    await outer.evaluate((element) => {
        element.scrollLeft = 100;
        element.scrollTop = 90;
    });
    const position = () =>
        frame.locator('body').evaluate(() => {
            const nested = document.querySelector('.scrollbox');
            return {
                left: scrollX,
                top: scrollY,
                nestedLeft: nested?.scrollLeft || 0,
                nestedTop: nested?.scrollTop || 0,
            };
        });
    await expect.poll(position).toEqual({ left: 130, top: 240, nestedLeft: 90, nestedTop: 140 });
    return { html, css, preview, frame, outer, position };
}

test('HTML and CSS edits retain preview, nested and outer scroll positions', async ({ page }) => {
    const { html, css, preview, frame, outer, position } = await preparePreview(page);
    const expected = await position();
    const outerPosition = () =>
        outer.evaluate((element) => ({ left: element.scrollLeft, top: element.scrollTop }));
    const expectedOuter = await outerPosition();
    expect(expectedOuter.left).toBeGreaterThan(0);
    expect(expectedOuter.top).toBeGreaterThan(0);
    await css.press('ControlOrMeta+End');
    await css.pressSequentially('\n/* CSS edit */');
    await expect(preview).toHaveAttribute('srcdoc', /CSS edit/);
    await expect.poll(position).toEqual(expected);
    await expect.poll(outerPosition).toEqual(expectedOuter);
    await page.getByRole('tab', { name: /HTML/ }).click();
    await html.press('ControlOrMeta+End');
    await html.pressSequentially(' HTML edit');
    await expect(frame.locator('body')).toContainText('HTML edit');
    await expect.poll(position).toEqual(expected);
    await expect.poll(outerPosition).toEqual(expectedOuter);
    expect(
        await page.locator('.editor-html .cm-scroller').evaluate((el) => el.scrollTop),
    ).toBeGreaterThan(0);
    await html.fill('<p>Short content</p>');
    await expect(frame.getByText('Short content', { exact: true })).toBeVisible();
    await expect.poll(position).toEqual({ left: 0, top: 0, nestedLeft: 0, nestedTop: 0 });
    await expect(page.locator('.preview-error')).toHaveCount(0);
});

test('scroll restoration exceptions are swallowed and editing continues at zero', async ({
    page,
}) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.addInitScript(() => {
        if (window !== parent) {
            const unavailable = () => {
                throw new Error('Scroll restoration unavailable');
            };
            window.scrollTo = unavailable;
            HTMLElement.prototype.scrollTo = unavailable;
        }
    });
    const { css, preview, html, frame, position } = await preparePreview(page);
    await css.press('ControlOrMeta+End');
    await css.pressSequentially('\n/* Fallback edit */');
    await expect(preview).toHaveAttribute('srcdoc', /Fallback edit/);
    await expect.poll(position).toEqual({ left: 0, top: 0, nestedLeft: 0, nestedTop: 0 });
    await page.getByRole('tab', { name: /HTML/ }).click();
    await html.press('ControlOrMeta+End');
    await html.pressSequentially(' Still editable');
    await expect(frame.locator('body')).toContainText('Still editable');
    await expect(page.locator('.preview-error')).toHaveCount(0);
    expect(errors).toEqual([]);
});
