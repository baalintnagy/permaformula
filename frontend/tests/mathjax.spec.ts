import { expect, test } from '@playwright/test';

test('MathJax 4 renders extra font glyphs entirely from local assets', async ({ page }) => {
    const mathRequests: string[] = [];
    const failed: string[] = [];
    page.on('request', (request) => {
        if (request.url().includes('/mathjax/')) {
            mathRequests.push(request.url());
        }
    });
    page.on('requestfailed', (request) => {
        if (request.url().includes('/mathjax/')) {
            failed.push(request.url());
        }
    });
    await page.goto('/');
    await page.getByRole('textbox', { name: 'AsciiMath formula', exact: true }).fill('↝');
    const preview = page.frameLocator('iframe[title="Formula live preview"]');
    await expect(preview.locator('[data-c="219D"]')).toBeVisible();
    const version = await preview.locator('html').evaluate(() => {
        return (window as Window & { MathJax: { version: string } }).MathJax.version;
    });
    expect(version).toBe('4.1.3');
    expect(mathRequests.some((url) => url.endsWith('/svg/dynamic/arrows.js'))).toBe(true);
    expect(mathRequests.every((url) => new URL(url).origin === new URL(page.url()).origin)).toBe(
        true,
    );
    expect(failed).toEqual([]);
});
