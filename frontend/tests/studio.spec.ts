import { expect, test } from '@playwright/test';

const svg =
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="50"><text x="10" y="30">Rendered image</text></svg>';
const permalink = 'http://127.0.0.1:5173/image?ubcq=test%2Bpayload';

test('local math previews, exact query encoding, server rendering and new-tab permalink flow', async ({
  page,
}) => {
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.route('https://example.com/*.css', (route) =>
    route.fulfill({ contentType: 'text/css', body: '.caption { font-weight: 500; }' }),
  );
  const requests: string[] = [];
  page.on('request', (request) => {
    if (['/img', '/image', '/url', '/url-encode'].includes(new URL(request.url()).pathname))
      requests.push(request.url());
  });
  await page.route('**/img?**', (route) =>
    route.fulfill({ contentType: 'image/svg+xml', body: svg }),
  );
  await page
    .context()
    .route('**/image?**', (route) => route.fulfill({ contentType: 'image/svg+xml', body: svg }));
  await page.route('**/url?**', (route) =>
    route.fulfill({ contentType: 'text/plain', body: permalink }),
  );
  await page.goto('/');
  await expect(
    page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
  ).toBeVisible();
  await expect(
    page.frameLocator('iframe[title="HTML live preview"]').locator('.math-card'),
  ).toHaveCSS('background-color', 'rgb(245, 243, 255)');
  await expect(
    page.frameLocator('iframe[title="Formula live preview"]').locator('mjx-assistive-mml'),
  ).toHaveCSS('position', 'absolute');
  await expect(page.frameLocator('iframe[title="HTML live preview"]').locator('svg')).toBeVisible();
  expect(requests).toHaveLength(0);

  const formula = 'sqrt(x^2 + y^2) + α & 50%';
  await page.getByRole('textbox', { name: 'ASCIIMath formula', exact: true }).fill(formula);
  await expect
    .poll(async () =>
      new URL(
        await page.getByLabel('Formula image URL', { exact: true }).inputValue(),
      ).searchParams.get('formula'),
    )
    .toBe(formula);
  await page.getByRole('button', { name: 'Copy Formula image URL', exact: true }).click();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
    await page.getByLabel('Formula image URL', { exact: true }).inputValue(),
  );
  await page.getByRole('button', { name: 'Render image', exact: true }).click();
  await expect(
    page.getByRole('img', { name: `Rendered formula: ${formula}`, exact: true }),
  ).toBeVisible();
  expect(
    new URL(requests.find((url) => new URL(url).pathname === '/img')!).searchParams.get('formula'),
  ).toBe(formula);

  await page.getByRole('button', { name: 'Add stylesheet' }).click();
  await page.getByLabel('Stylesheet URL 1').fill('https://example.com/one.css');
  await page.getByRole('button', { name: 'Add stylesheet' }).click();
  await page.getByLabel('Stylesheet URL 2').fill('https://example.com/two.css');
  await page.getByRole('button', { name: 'Move stylesheet 2 up' }).click();
  await page.getByLabel('Canvas width').fill('720');
  const popupPromise = page.waitForEvent('popup');
  await page.getByRole('button', { name: 'Generate & open image' }).click();
  const popup = await popupPromise;
  await popup.waitForURL(permalink);
  await expect(page.getByLabel('Generated image permalink', { exact: true })).toHaveValue(
    permalink,
  );
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
  await page.screenshot({ path: 'test-results/studio-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: 'test-results/studio-desktop.png', fullPage: true });
});

test('validation, ProblemDetail failures and popup fallback are visible', async ({ page }) => {
  await page.route('**/img?**', (route) =>
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
  await page.getByRole('button', { name: 'Render image', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Invalid formula input');
  await page.getByLabel('Canvas width').fill('219');
  await expect(page.getByRole('button', { name: 'Generate & open image' })).toBeDisabled();
  await page.getByLabel('Canvas width').fill('800');
  await page.getByRole('button', { name: 'Add stylesheet' }).click();
  await page.getByLabel('Stylesheet URL 1').fill('http://example.com/style.css');
  await expect(page.getByRole('button', { name: 'Generate & open image' })).toBeDisabled();
  await page.getByRole('button', { name: 'Remove stylesheet 1' }).click();
  await page.evaluate(() => {
    window.open = () => null;
  });
  await page.getByRole('button', { name: 'Generate & open image' }).click();
  await expect(page.getByText('Your browser blocked the new tab.')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Open image', exact: true })).toHaveAttribute(
    'href',
    permalink,
  );
});

test('initial studio renders in desktop and mobile light/dark layouts', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.goto('/');
  await expect(page.frameLocator('iframe[title="HTML live preview"]').locator('svg')).toBeVisible();
  await expect(
    page.frameLocator('iframe[title="Formula live preview"]').locator('svg'),
  ).toBeVisible();
  await page.screenshot({ path: 'test-results/studio-light-desktop.png', fullPage: true });
  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await page.screenshot({ path: 'test-results/studio-dark-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: 'Formula playground' })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/studio-dark-mobile.png', fullPage: true });
  await page.getByRole('button', { name: 'Switch to light theme' }).click();
  await page.screenshot({ path: 'test-results/studio-light-mobile.png', fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.locator('#composer').scrollIntoViewIfNeeded();
  await page.screenshot({ path: 'test-results/studio-composer-desktop.png' });
});
