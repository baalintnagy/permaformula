import { expect, test } from '@playwright/test';

test('keeps the last render visible until its replacement is ready', async ({ page }) => {
    let release!: () => void;
    let gate = Promise.resolve();
    let fail = false;
    let requests = 0;
    await page.context().route('**/mathjax/startup.js', async (route) => {
        requests++;
        await gate;
        if (fail) {
            await route.fulfill({
                contentType: 'application/javascript',
                body: "parent.postMessage({ type: 'permaformula-preview', ok: false }, '*');",
            });
        } else {
            await route.continue();
        }
    });
    await page.goto('/');
    const visible = page.locator('iframe[title="Formula live preview"]');
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
    ).toBeVisible();
    const original = await visible.getAttribute('srcdoc');
    const initialRequests = requests;
    gate = new Promise<void>((resolve) => {
        release = resolve;
    });
    await page.getByRole('textbox', { name: 'AsciiMath formula', exact: true }).fill('sqrt(123)');
    const pending = page.locator('iframe[title="Formula live preview (updating)"]');
    await expect(pending).toHaveCount(1);
    await expect.poll(() => requests).toBeGreaterThan(initialRequests);
    await expect(pending).toBeHidden();
    await expect(visible).toHaveAttribute('srcdoc', original!);
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
    ).toBeVisible();
    release();
    await expect(pending).toHaveCount(0);
    await expect(visible).not.toHaveAttribute('srcdoc', original!);
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
    ).toBeVisible();

    const latest = await visible.getAttribute('srcdoc');
    const successfulRequests = requests;
    fail = true;
    gate = new Promise<void>((resolve) => {
        release = resolve;
    });
    await page.getByRole('textbox', { name: 'AsciiMath formula', exact: true }).fill('sqrt(456)');
    await expect(pending).toHaveCount(1);
    await expect.poll(() => requests).toBeGreaterThan(successfulRequests);
    await expect(visible).toHaveAttribute('srcdoc', latest!);
    release();
    await expect(page.locator('.formula-preview [role="alert"]')).toContainText(
        'Preview unavailable',
        { timeout: 20000 },
    );
    await expect(visible).toHaveAttribute('srcdoc', latest!);
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
    ).toBeVisible();
});

test('builder and math preview work when randomUUID is unavailable on HTTP', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.addInitScript(() => {
        Object.defineProperty(crypto, 'randomUUID', { value: undefined, configurable: true });
    });
    await page.goto('/');
    await expect(page.locator('#root')).not.toBeEmpty();
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
    ).toBeVisible();
    await expect(
        page.frameLocator('iframe[title="HTML live preview"]').locator('svg'),
    ).toBeVisible();
    expect(errors).toEqual([]);
});
