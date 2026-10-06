package org.permmath.rendering;

import com.microsoft.playwright.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.net.URI;

/**
 * All methods are invoked on the service's single rendering thread.
 */
final class ChromiumRenderer implements AutoCloseable {

    private final Playwright playwright;

    private final Browser browser;

    private final double timeout;
    private final org.permmath.contract.ComplexityPolicy policy;

    ChromiumRenderer(BrowserSettings properties) throws IOException {
        this(properties, org.permmath.contract.ComplexityPolicy.CURRENT);
    }

    ChromiumRenderer(BrowserSettings properties, org.permmath.contract.ComplexityPolicy policy) throws IOException {
        this.policy = java.util.Objects.requireNonNull(policy);
        var directory = properties.browserDirectory().toAbsolutePath().normalize();
        // Only executables in the configured browser installation are considered.
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                "PLAYWRIGHT_BROWSERS_PATH", directory.toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        try {
            var executable = Path.of(playwright.chromium().executablePath()).toAbsolutePath().normalize();
            if (!executable.startsWith(directory) || !Files.isRegularFile(executable)) {
                throw new IOException("No compatible Chromium installed in " + directory
                        + "; run scripts/install-rendering.ps1");
            }
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setExecutablePath(executable).setHeadless(true)
                    .setChromiumSandbox(properties.chromiumSandbox()));
        } catch (RuntimeException | IOException e) {
            playwright.close();
            throw e;
        }
        timeout = properties.timeout().toMillis();
    }

    byte[] render(String url, int width) {
        try (var context = BrowserChannelPolicy.newContext(browser, width)) {
            context.addInitScript("globalThis.PermMathComplexity = " + policy.json() + ";");
            var page = BrowserChannelPolicy.newPage(context, url);
            page.setDefaultTimeout(timeout);
            page.setDefaultNavigationTimeout(timeout);
            page.navigate(url);
            page.waitForFunction("window.PermMathRenderer?.state === 'ready' || window.PermMathRenderer?.state === 'error'");
            if ("error".equals(page.evaluate("window.PermMathRenderer.state"))) {
                throw new IllegalArgumentException("Typesetting failed: " + page.evaluate("window.PermMathRenderer.error"));
            }
            return ((String) page.evaluate("window.PermMathRenderer.svg")).getBytes(StandardCharsets.UTF_8);
        }
    }

    static boolean allowedRequest(String documentUrl, String requestedUrl, String method, String type) {
        if (!"GET".equals(method)) {
            return false;
        }
        try {
            var document = URI.create(documentUrl);
            var request = URI.create(requestedUrl);
            if (!"http".equals(request.getScheme()) || request.getRawUserInfo() != null || !document.getHost().equals(request.getHost()) || document.getPort() != request.getPort() || request.getRawQuery() != null || request.getRawFragment() != null) {
                return false;
            }
            if ("document".equals(type)) {
                return documentUrl.equals(requestedUrl);
            }
            if (!"script".equals(type)) {
                return false;
            }
            var path = request.getRawPath();
            return "/export-svg.js".equals(path)
                    || (path.matches("/mathjax/[a-zA-Z0-9_/-]+\\.js") && !path.contains(".."));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void close() {
        browser.close();
        playwright.close();
    }
}
