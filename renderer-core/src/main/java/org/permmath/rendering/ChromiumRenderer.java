package org.permmath.rendering;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.permmath.contract.ComplexityPolicy;

import com.microsoft.playwright.*;

/**
 * All methods are invoked on the service's single rendering thread.
 */
final class ChromiumRenderer implements AutoCloseable {

    private final Playwright playwright;

    private final Browser browser;

    private final double timeout;
    private final ComplexityPolicy policy;

    ChromiumRenderer(BrowserSettings properties) throws IOException {
        this(properties, ComplexityPolicy.CURRENT);
    }

    ChromiumRenderer(BrowserSettings properties, ComplexityPolicy policy) throws IOException {
        long started = System.nanoTime();
        this.policy = Objects.requireNonNull(policy);
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
        RenderTimings.elapsed("browser_initialize", started);
    }

    byte[] render(String url, int width) {
        long started = System.nanoTime();
        try (var context = BrowserChannelPolicy.newContext(browser, width)) {
            context.addInitScript("globalThis.PermMathComplexity = " + policy.json() + ";");
            var page = BrowserChannelPolicy.newPage(context, url);
            page.setDefaultTimeout(timeout);
            page.setDefaultNavigationTimeout(timeout);
            RenderTimings.elapsed("browser_context", started);
            started = System.nanoTime();
            page.navigate(url);
            RenderTimings.elapsed("browser_navigation", started);
            started = System.nanoTime();
            page.waitForFunction("window.PermMathRenderer?.state === 'ready' || window.PermMathRenderer?.state === 'error'");
            RenderTimings.elapsed("browser_typeset_wait", started);
            if ("error".equals(page.evaluate("window.PermMathRenderer.state"))) {
                throw new IllegalArgumentException("Typesetting failed: " + page.evaluate("window.PermMathRenderer.error"));
            }
            started = System.nanoTime();
            byte[] svg = ((String) page.evaluate("window.PermMathRenderer.svg")).getBytes(StandardCharsets.UTF_8);
            RenderTimings.elapsed("browser_svg_extract", started);
            return svg;
        }
    }

    byte[] jpeg(String url, String svg) {
        try (var context = BrowserChannelPolicy.newContext(browser, 500)) {
            var page = BrowserChannelPolicy.newPage(context, url);
            page.setDefaultTimeout(timeout);
            page.setDefaultNavigationTimeout(timeout);
            page.navigate(url);
            String encoded = (String) page.evaluate("""
                async input => {
                    const root = new DOMParser().parseFromString(input.svg, 'image/svg+xml').documentElement;
                    const dimension = value => {
                        const match = /^(\\d+(?:\\.\\d+)?)(px|ex|em)?$/.exec(value || '');
                        if (!match) throw new Error('Invalid SVG dimensions');
                        return Number(match[1]) * (match[2] === 'ex' ? input.ex : match[2] === 'em' ? input.em : 1);
                    };
                    const w = dimension(root.getAttribute('width'));
                    const h = dimension(root.getAttribute('height'));
                    const scale = Math.min(2, input.dimension / w, input.dimension / h, Math.sqrt(input.area / (w * h)));
                    if (scale < 1) throw new Error('SVG exceeds raster limits');
                    const width = Math.floor(w * scale), height = Math.floor(h * scale);
                    if (width < 1 || height < 1) throw new Error('Invalid raster dimensions');
                    root.setAttribute('width', String(w)); root.setAttribute('height', String(h));
                    const image = new Image();
                    image.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(new XMLSerializer().serializeToString(root));
                    await image.decode();
                    const canvas = document.createElement('canvas');
                    canvas.width = width; canvas.height = height;
                    const ctx = canvas.getContext('2d');
                    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, width, height);
                    ctx.drawImage(image, 0, 0, width, height);
                    const output = canvas.toDataURL('image/jpeg', 0.95);
                    if (!output.startsWith('data:image/jpeg;base64,')) throw new Error('JPEG encoding failed');
                    return output.substring(output.indexOf(',') + 1);
                }
                """, Map.of("svg", svg,
                    "dimension", policy.get(ComplexityPolicy.Limit.RENDER_DIMENSION),
                    "area", policy.get(ComplexityPolicy.Limit.RENDER_AREA),
                    "ex", policy.get(ComplexityPolicy.Limit.EX_PIXELS),
                    "em", policy.get(ComplexityPolicy.Limit.EM_PIXELS)));
            return java.util.Base64.getDecoder().decode(encoded);
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
