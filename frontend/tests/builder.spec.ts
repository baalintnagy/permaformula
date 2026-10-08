import { expect, test } from '@playwright/test';

const svg =
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="50"><text x="10" y="30">Rendered SVG</text></svg>';
const permalink = 'http://127.0.0.1:5173/html?ubcq=test%2Bpayload';

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
  await page.getByRole('slider', { name: 'Formula preview zoom' }).fill('200');
  await expect(formulaFrame.locator('#formula-preview')).toHaveCSS('font-size', '32px');
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
  await page.setViewportSize({ width: 390, height: 844 });
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
    if (['/exp', '/html', '/url', '/url-encode'].includes(new URL(request.url()).pathname))
      requests.push(request.url());
  });
  await page
    .context()
    .route('**/exp?**', (route) => route.fulfill({ contentType: 'image/svg+xml', body: svg }));
  await page
    .context()
    .route('**/html?**', (route) => route.fulfill({ contentType: 'image/svg+xml', body: svg }));
  await page.route('**/url?**', (route) =>
    route.fulfill({ contentType: 'text/plain', body: permalink }),
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
  await expect(page.frameLocator('iframe[title="HTML live preview"]').locator('svg')).toBeVisible();
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
  expect(requests.some((url) => new URL(url).pathname === '/exp')).toBe(false);
  const formulaPopupPromise = page.waitForEvent('popup');
  await formulaLink.click();
  const formulaPopup = await formulaPopupPromise;
  await formulaPopup.waitForURL(currentFormulaUrl);
  await formulaPopup.close();
  await formulaSection.getByRole('button', { name: 'Generate SVG', exact: true }).click();
  await expect(
    page.getByRole('img', { name: `Rendered formula: ${formula}`, exact: true }),
  ).toBeVisible();
  expect(
    new URL(requests.find((url) => new URL(url).pathname === '/exp')!).searchParams.get('formula'),
  ).toBe(formula);

  await page.getByRole('button', { name: 'Add stylesheet' }).click();
  await page.getByLabel('Stylesheet URL 1').fill('https://example.com/one.css');
  await page.getByRole('button', { name: 'Add stylesheet' }).click();
  await page.getByLabel('Stylesheet URL 2').fill('https://example.com/two.css');
  await page.getByRole('button', { name: 'Move stylesheet 2 up' }).click();
  await page.getByLabel('Canvas width').fill('720');
  let popupCount = 0;
  page.on('popup', () => popupCount++);
  await page.locator('#composer').getByRole('button', { name: 'Generate SVG' }).click();
  await expect(page.frameLocator('iframe[title="Generated SVG"]').locator('svg')).toBeVisible();
  expect(popupCount).toBe(0);
  await expect(page.locator('.generated-viewer')).toHaveCSS('animation-name', 'result-ready');
  await page.getByLabel('Canvas width').fill('500');
  await expect(page.getByText('Showing the previous SVG.')).toBeVisible();
  await page.getByLabel('Canvas width').fill('720');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(page.locator('.generated-viewer')).toHaveCSS('animation-name', 'none');
  await page.locator('#composer').getByRole('button', { name: 'Generate SVG' }).click();
  await expect(page.frameLocator('iframe[title="Generated SVG"]').locator('svg')).toBeVisible();
  await expect(page.getByText('Your SVG is ready.', { exact: true })).toBeVisible();
  expect(popupCount).toBe(0);
  const popupPromise = page.waitForEvent('popup');
  await page.locator('#composer').getByRole('link', { name: 'Open SVG', exact: true }).click();
  const popup = await popupPromise;
  await popup.waitForURL(permalink);
  await expect(page.getByLabel('Generated SVG URL', { exact: true })).toHaveValue(permalink);
  const request = new URL(requests.find((url) => new URL(url).pathname === '/url')!);
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
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: 'test-results/builder-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: 'test-results/builder-desktop.png', fullPage: true });
});

test('validation and server rendering failures are visible', async ({ page }) => {
  await page.context().route('**/exp?**', (route) =>
    route.fulfill({
      status: 400,
      contentType: 'application/problem+json',
      body: JSON.stringify({ detail: 'Invalid formula input' }),
    }),
  );
  await page.route('**/url?**', (route) =>
    route.fulfill({ contentType: 'text/plain', body: permalink }),
  );
  await page.goto('/');
  await page.locator('#formula').getByRole('button', { name: 'Generate SVG', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Invalid formula input');
  await page.getByLabel('Canvas width').fill('219');
  await expect(
    page.locator('#composer').getByRole('button', { name: 'Generate SVG' }),
  ).toBeDisabled();
  await page.getByLabel('Canvas width').fill('500');
  await page.getByRole('button', { name: 'Add stylesheet' }).click();
  await page.getByLabel('Stylesheet URL 1').fill('http://example.com/style.css');
  await expect(
    page.locator('#composer').getByRole('button', { name: 'Generate SVG' }),
  ).toBeDisabled();
  await page.getByRole('button', { name: 'Remove stylesheet 1' }).click();
  await page.route('**/html?**', (route) =>
    route.fulfill({
      status: 400,
      contentType: 'application/problem+json',
      body: JSON.stringify({ detail: 'Unable to render HTML' }),
    }),
  );
  await page.locator('#composer').getByRole('button', { name: 'Generate SVG' }).click();
  await expect(page.locator('#composer').getByRole('alert')).toHaveText('Unable to render HTML');
  await expect(
    page.locator('#composer').getByRole('link', { name: 'Open SVG', exact: true }),
  ).toHaveCount(0);
  await page.route('**/html?**', (route) =>
    route.fulfill({ contentType: 'image/svg+xml', body: svg }),
  );
  await page.locator('#composer').getByRole('button', { name: 'Generate SVG' }).click();
  await expect(page.frameLocator('iframe[title="Generated SVG"]').locator('svg')).toBeVisible();
  await expect(
    page.locator('#composer').getByRole('link', { name: 'Open SVG', exact: true }),
  ).toHaveAttribute('href', permalink);
});

test('initial builder renders in desktop and mobile light/dark layouts', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto('/');
  await expect(page.frameLocator('iframe[title="HTML live preview"]').locator('svg')).toBeVisible();
  await expect(
    page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
  ).toBeVisible();
  await page.screenshot({ path: 'test-results/builder-light-desktop.png', fullPage: true });
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await page.screenshot({ path: 'test-results/builder-dark-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: 'Simple expression' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/builder-dark-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Switch to light theme' }).click();
  await page.screenshot({ path: 'test-results/builder-light-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.locator('#composer').scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'test-results/builder-composer-desktop.png' });
});
