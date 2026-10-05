package org.permmath.rendering;

import com.microsoft.playwright.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** All methods are invoked on the service's single rendering thread. */
final class ChromiumRenderer implements AutoCloseable {
    private final Playwright playwright;
    private final Browser browser;
    private final double timeout;

    ChromiumRenderer(RenderingProperties properties) throws IOException {
        var directory = properties.browserDirectory().toAbsolutePath().normalize();
        // Only executables in the configured project-local directory are considered.
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                "PLAYWRIGHT_BROWSERS_PATH", directory.toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        try {
            var executable = Path.of(playwright.chromium().executablePath()).toAbsolutePath().normalize();
            if (!executable.startsWith(directory) || !Files.isRegularFile(executable)) {
                throw new IOException("No compatible Chromium installed in " + directory
                        + "; run scripts/install-rendering.ps1");
            }
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setExecutablePath(executable).setHeadless(true));
        } catch (RuntimeException | IOException e) {
            playwright.close();
            throw e;
        }
        timeout = properties.timeout().toMillis();
    }

    byte[] render(String url, int width) {
        try (var context = browser.newContext(new Browser.NewContextOptions().setViewportSize(width, 600))) {
            var page = context.newPage();
            page.setDefaultTimeout(timeout);
            page.setDefaultNavigationTimeout(timeout);
            page.navigate(url);
            page.waitForFunction("window.renderState === 'ready' || window.renderState === 'error'");
            if ("error".equals(page.evaluate("window.renderState"))) {
                throw new IllegalArgumentException("Typesetting failed: " + page.evaluate("window.renderError"));
            }
            return ((String) page.evaluate("window.renderSvg")).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override public void close() { browser.close(); playwright.close(); }
}
