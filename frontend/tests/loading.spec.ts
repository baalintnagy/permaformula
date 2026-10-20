import { expect, test } from '@playwright/test';

for (const [path, entry] of [
    ['/', 'main'],
    ['/admin.html', 'admin'],
]) {
    test(`${path} hides unstyled content during initial load and refresh`, async ({ page }) => {
        await page.emulateMedia({ colorScheme: 'dark', reducedMotion: 'no-preference' });
        let release!: () => void;
        let gate: Promise<void>;
        await page.route(`**/src/${entry}.tsx`, async (route) => {
            await gate;
            await route.continue();
        });
        for (const refresh of [false, true]) {
            gate = new Promise<void>((resolve) => {
                release = resolve;
            });
            if (refresh) {
                await page.reload({ waitUntil: 'commit' });
            } else {
                await page.goto(path, { waitUntil: 'commit' });
            }
            await expect(page.getByRole('status')).toHaveText('Loading PermaFormula…');
            await expect(page.locator('#root')).toHaveCSS('visibility', 'hidden');
            await expect(page.locator('html')).toHaveCSS('background-color', 'rgb(18, 19, 24)');
            release();
            await expect(page.locator('html')).toHaveAttribute('data-app-state', 'ready');
            await expect(page.locator('#app-loading')).toHaveCount(0);
            await expect(page.locator('#root')).toHaveCSS('visibility', 'visible');
            await expect(page.locator('#root')).toHaveCSS('animation-duration', '0.5s');
        }
    });
}

test('the crawlable fallback stays visible with JavaScript disabled', async ({ browser }) => {
    const context = await browser.newContext({ javaScriptEnabled: false });
    const page = await context.newPage();
    await page.goto('/');
    await expect(page.locator('#root h1')).toBeVisible();
    await expect(page.locator('#app-loading')).toBeHidden();
    await context.close();
});
