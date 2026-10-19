import { expect, test } from '@playwright/test';

test('phone hamburger menu works on main and admin pages', async ({ page }) => {
    await page.setViewportSize({ width: 320, height: 780 });
    for (const path of ['/', '/admin.html']) {
        await page.goto(path);
        const navigation = page.locator('nav[aria-label="Workspaces"]');
        await expect(navigation).toBeHidden();
        await page.getByRole('button', { name: 'Open navigation menu' }).click();
        for (const label of [
            'Simple expression',
            'Formula + HTML + CSS',
            'Decode UBCQ',
            'Library',
        ]) {
            await expect(navigation.getByRole('link', { name: label, exact: true })).toBeVisible();
        }
        expect(
            await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
        ).toBe(true);
        await expect(page.getByRole('button', { name: /Switch to .* theme/ })).toBeVisible();
        await page.keyboard.press('Escape');
        await expect(navigation).toBeHidden();
        await expect(page.getByRole('button', { name: 'Open navigation menu' })).toBeFocused();
    }
    await page.goto('/');
    await page.getByRole('button', { name: 'Open navigation menu' }).click();
    await page.getByRole('navigation').getByRole('link', { name: 'Library', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Rendered library' })).toBeVisible();
    await expect(page.locator('nav[aria-label="Workspaces"]')).toBeHidden();
});

test('HTML live preview uses its own styles independently of the host theme', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.goto('/');
    await page
        .getByRole('textbox', { name: 'HTML source', exact: true })
        .fill('<p>Independent preview</p>');
    await page.getByRole('tab', { name: /CSS/ }).click();
    const css = page.getByRole('textbox', { name: 'CSS source', exact: true });
    await css.fill('');
    const preview = page.frameLocator('iframe[title="HTML live preview"]');
    await expect(preview.locator('p')).toHaveText('Independent preview');
    await expect(preview.locator('html')).toHaveCSS('background-color', 'rgb(255, 255, 255)');
    await expect(preview.locator('body')).toHaveCSS('color', 'rgb(0, 0, 0)');
    await expect(page.locator('iframe[title="HTML live preview"]')).toHaveCSS(
        'color-scheme',
        'light',
    );
    const source = await page.locator('iframe[title="HTML live preview"]').getAttribute('srcdoc');
    await page.getByRole('button', { name: 'Switch to light theme' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await expect(page.locator('iframe[title="HTML live preview"]')).toHaveAttribute(
        'srcdoc',
        source!,
    );
    await expect(preview.locator('body')).toHaveCSS('color', 'rgb(0, 0, 0)');
    await css.fill('html { background: #123456; color: #abcdef; }');
    await expect(preview.locator('html')).toHaveCSS('background-color', 'rgb(18, 52, 86)');
    await expect(preview.locator('body')).toHaveCSS('color', 'rgb(171, 205, 239)');
});
test('theme follows OS until explicitly overridden', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.goto('/');
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.locator('.preview-container').first()).toHaveCSS(
        'background-color',
        'rgb(20, 21, 26)',
    );
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('html'),
    ).toHaveCSS('background-color', 'rgb(20, 21, 26)');
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('body'),
    ).toHaveCSS('color', 'rgb(228, 233, 244)');
    await expect(
        page.frameLocator('iframe[title="HTML live preview"]').locator('.math-card'),
    ).toHaveCSS('background-color', 'rgb(237, 243, 251)');
    await page.emulateMedia({ colorScheme: 'light' });
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
    await expect(page.locator('.preview-container').first()).toHaveCSS(
        'background-color',
        'rgb(255, 255, 255)',
    );
    await expect(
        page.frameLocator('iframe[title="Formula live preview"]').locator('html'),
    ).toHaveCSS('background-color', 'rgb(231, 231, 236)');
    await page.getByRole('button', { name: 'Switch to dark theme' }).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.getByRole('button', { name: 'Use OS theme' })).toHaveCount(0);
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.emulateMedia({ colorScheme: 'light' });
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
});
