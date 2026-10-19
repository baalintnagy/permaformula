import { defineConfig } from '@playwright/test';

const port = Number(process.env.PERMAFORMULA_TEST_PORT || 5173);

export default defineConfig({
    testDir: './tests',
    fullyParallel: false,
    workers: 2,
    expect: { timeout: 15000 },
    use: {
        baseURL: `http://127.0.0.1:${port}`,
        headless: true,
        launchOptions: process.env.PERMAFORMULA_TEST_CHROMIUM
            ? { executablePath: process.env.PERMAFORMULA_TEST_CHROMIUM }
            : {},
    },
    webServer: {
        command: `npm run dev -- --port ${port} --strictPort`,
        url: `http://127.0.0.1:${port}`,
        reuseExistingServer: false,
        env: {
            PERMAFORMULA_DEV_API: 'http://localhost:8086',
            PERMAFORMULA_DEV_UI_ORIGIN: `http://127.0.0.1:${port}`,
        },
        timeout: 30000,
    },
});
