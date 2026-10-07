package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.permmath.PermMathController;
import org.permmath.PermMathProperties;
import org.permmath.contract.ComplexityPolicy;
import org.permmath.url.ImageDescriptor;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Playwright;
import com.sun.net.httpserver.HttpServer;

import io.swagger.v3.oas.annotations.Parameter;

@EnabledIfSystemProperty(named = "rendering.tests", matches = "true")
class BrowserRenderingTests {
    @Test
    void configuredPolicyReachesChromiumBeforeDocumentScripts() throws Exception {
        var policy = ComplexityPolicy.load(new Properties(),
                Map.of("PERMMATH_COMPLEXITY_RENDER_DIMENSION", "1000"));
        String template;
        try (var input = getClass().getResourceAsStream("/wrapper.html")) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("</body>", "<div style='height:1200px'>Too tall</div></body>");
        }
        try (var assets = new RenderAssets(); var renderer = new ChromiumRenderer(new BrowserSettings(
                Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false), policy)) {
            assertThrows(IllegalArgumentException.class, () -> renderer.render(assets.add(template), 320));
        }
    }

    @Test
    void mathJaxDiscoveryBoundsExpressionCountAndNativeTexExpansion() throws Exception {
        String template;
        try (var input = getClass().getResourceAsStream("/wrapper.html")) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (var assets = new RenderAssets(); var renderer = new ChromiumRenderer(new BrowserSettings(
                Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
            String many = template.replace("</body>", "<p>`x`</p>".repeat(2049) + "</body>");
            var count = assertThrows(IllegalArgumentException.class, () -> renderer.render(assets.add(many), 320));
            assertTrue(count.getMessage().contains("Math expression count"));
            String loop = template.replace("</body>", "<p>\\(\\def\\boom{\\boom}\\boom\\)</p></body>");
            var expansion = assertThrows(IllegalArgumentException.class, () -> renderer.render(assets.add(loop), 320));
            assertTrue(expansion.getMessage().contains("Invalid math expression"));
        }
    }
    @Test
    void mathJaxChecksActualExpressionsBeforeCompilation() throws Exception {
        String template;
        try (var input = getClass().getResourceAsStream("/wrapper.html")) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("</body>", "<p>\\(" + "x".repeat(262145) + "\\)</p></body>");
        }
        try (var assets = new RenderAssets(); var renderer = new ChromiumRenderer(new BrowserSettings(
                Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> renderer.render(assets.add(template), 320));
            assertTrue(failure.getMessage().contains("Math expression exceeds size limit"));
        }
    }
    @Test
    void rejectsExtremeLayoutAndRecoversForGenerousTallDocument() throws Exception {
        try (var renderer = imageRenderer(new PermMathProperties("http://localhost:8086", 800, 2000),
                new BrowserSettings(Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
            assertThrows(IllegalArgumentException.class, () -> renderer.getImage(
                    new ImageDescriptor("<div>Too tall</div>", 1000, null, "div{height:40000px}")));
            try (var input = renderer.getImage(new ImageDescriptor("<div>Generous document</div>",
                    1000, null, "div{height:30000px}")).getInputStream()) {
                assertTrue(SvgOutputPolicy.sanitize(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("Generous document"));
            }
        }
    }
    @Test
    void reportsMathJaxLoadFailureThroughRendererState() throws Exception {
        String template;
        try (var input = getClass().getResourceAsStream("/wrapper.html")) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("/mathjax/tex-svg.js", "/mathjax/missing.js");
        }
        try (var assets = new RenderAssets();
                var renderer = new ChromiumRenderer(new BrowserSettings(
                        Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> renderer.render(assets.add(template), 320));
            assertTrue(failure.getMessage().contains("MathJax failed to load"));
        }
    }

    @Test
    void mathJaxSafeModeBlocksGeneratedActiveLinksAndRetainsSafeMath() throws Exception {
        try (var renderer = imageRenderer(new PermMathProperties("http://localhost:8086", 800, 2000),
                new BrowserSettings(Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
            String html = "<p>\\(\\href{javascript:alert(1)}{probe}\\)</p>"
                    + "<p>\\(\\href{file:///etc/passwd}{file}\\)</p>"
                    + "<p>\\(\\href{data:text/html,evil}{data}\\)</p>"
                    + "<p>\\(\\href{https://example.com/math}{safe}\\)</p><p>`sqrt(x^2)`</p>";
            try (var input = renderer.getImage(new ImageDescriptor(html, 320, null, null)).getInputStream()) {
                String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(raw.contains("javascript:alert"));
                assertFalse(raw.contains("file:///"));
                assertFalse(raw.contains("data:text/html"));
                Files.writeString(Path.of("target/safe-math-raw.svg"), raw);
                String safe = SvgOutputPolicy.sanitize(raw);
                assertTrue(safe.contains("https://example.com/math"));
                assertTrue(parse(safe).getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").getLength() > 0);
            }
        }
    }
    @Test
    void hostileContentCannotExecuteOrContactLoopback() throws Exception {
        var hits = new AtomicInteger();
        var tripwire = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tripwire.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        tripwire.start();
        var target = "http://127.0.0.1:" + tripwire.getAddress().getPort() + "/";
        try (var renderer = imageRenderer(new PermMathProperties("http://localhost:8086", 800, 2000),
                new BrowserSettings(Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
            var input = "<div onclick='alert(1)'>Safe `x^2`</div>"
                    + "<script>window.PermMathRenderer={svg:'FORGED',state:'ready'};fetch('" + target + "')</script>"
                    + "<img src='" + target + "' onerror='window.PermMathRenderer.svg=\"FORGED\"'>"
                    + "<iframe src='" + target + "'></iframe>";
            try (var image = renderer.getImage(new ImageDescriptor(input, 320, null,
                    "@import url('" + target + "'); div {color:red;background-image:url('" + target + "')}"))
                    .getInputStream()) {
                var svg = new String(image.readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("svg", parse(svg).getDocumentElement().getLocalName());
                assertTrue(svg.contains("Safe"));
                assertFalse(svg.contains("FORGED"));
                assertFalse(svg.contains("onclick"));
                assertEquals(0, hits.get());
            }
        } finally {
            tripwire.stop(0);
        }
    }

    @Test
    void rendersBuilderSwaggerExample() throws Exception {
        var method = PermMathController.class.getMethod("getUrl", String.class,
                Integer.class, List.class, String.class);
        var parameters = method.getParameters();
        List<String> refs = List.of();
        var example = new ImageDescriptor(
                parameters[0].getAnnotation(Parameter.class).example(), null, refs,
                parameters[3].getAnnotation(Parameter.class).example());
        var settings = new BrowserSettings(Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false);
        try (var renderer = imageRenderer(
                new PermMathProperties("http://localhost:8086", 800, 2000), settings)) {
            var image = renderer.getImage(new ImageDescriptor(example.forumla(), 500, example.styleRefs(), example.style()));
            var directory = Path.of("target/rendering");
            Files.createDirectories(directory);
            var destination = directory.resolve("chromium.svg");
            try (var input = image.getInputStream()) {
                String raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                Files.writeString(directory.resolve("chromium-raw.svg"), raw);
                Files.writeString(destination, SvgOutputPolicy.sanitize(raw));
            }
            var decoded = parse(Files.readString(destination));
            assertEquals("svg", decoded.getDocumentElement().getLocalName());
            assertEquals("500", decoded.getDocumentElement().getAttribute("width"));
            assertTrue(Integer.parseInt(decoded.getDocumentElement().getAttribute("height")) > 100);
            assertTrue(decoded.getElementsByTagNameNS("http://www.w3.org/2000/svg", "foreignObject").getLength() > 0);
            assertTrue(decoded.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").getLength() > 0);
            assertTrue(Files.readString(destination).contains("rgb(237,243,251)"),
                    "Card must use the Builder example background");
            assertTrue(Files.readString(destination).contains("The sum has a closed form:"));
            assertTrue(Files.readString(destination).contains("n-th triangular number"));
            verifySvgImagePreview(destination);
        }
    }

    @Test
    void inlineStylesRenderWithoutLeakingAndPrivateStylesAreRejected() throws Exception {
        var settings = new BrowserSettings(Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false);
        try (var renderer = imageRenderer(
                new PermMathProperties("http://localhost:8086", 800, 2000), settings)) {
            String ref = "http://127.0.0.1:1/style.css";
            assertThrows(IllegalArgumentException.class, () -> renderer.getImage(new ImageDescriptor("<div>Plain HTML</div>", 320, List.of(ref), null)));
            var first = renderer.getImage(new ImageDescriptor("<div>Plain HTML</div>", 320, null, "body {margin:0; background:rgb(180,30,30);} div {height:150px;}"));
            try (var input = first.getInputStream()) {
                var svg = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var image = parse(svg).getDocumentElement();
                assertEquals("320", image.getAttribute("width"));
                assertEquals("150", image.getAttribute("height"));
                  assertTrue(svg.contains("rgb(180, 30, 30)"));
            }
            var second = renderer.getImage(new ImageDescriptor("<div>New request</div>", 320, null,
                    "body {margin:0; background:rgb(30,30,180);} div {height:180px;}"));
            try (var input = second.getInputStream()) {
                var svg = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                var image = parse(svg).getDocumentElement();
                assertEquals("180", image.getAttribute("height"));
                  assertTrue(svg.contains("rgb(30, 30, 180)"));
                assertFalse(svg.contains("<script"));
            }
        }
    }
    private static ImageRendererService imageRenderer(PermMathProperties properties, BrowserSettings settings) throws Exception {
        return new ImageRendererService(properties, new RenderGateway() {
            public byte[] renderHtml(String document, int width) throws IOException {
                try (var assets = new RenderAssets(); var chromium = new ChromiumRenderer(settings)) {
                    return chromium.render(assets.add(document), width);
                }
            }
            public byte[] renderFormula(String formula) throws IOException {
                try (var renderer = new AsciiMathRenderer()) {
                    return renderer.render(formula).getBytes(StandardCharsets.UTF_8);
                }
            }
        });
    }
    private static Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }

    private static void verifySvgImagePreview(Path svgFile) throws Exception {
        var browsers = Path.of("../.runtime/browsers").toAbsolutePath();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] svg = Files.readAllBytes(svgFile);
        server.createContext("/preview.svg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.getResponseHeaders().set("Content-Security-Policy", SvgOutputPolicy.CSP);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.sendResponseHeaders(200, svg.length);
            try (var body = exchange.getResponseBody()) {
                body.write(svg);
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/preview.svg";
        try (var playwright = Playwright.create(
                new Playwright.CreateOptions().setEnv(Map.of(
                        "PLAYWRIGHT_BROWSERS_PATH", browsers.toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            try (var browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setExecutablePath(Path.of(playwright.chromium().executablePath())).setHeadless(true))) {
                var page = browser.newPage();
                page.setViewportSize(1000, 800);
                page.setContent("<body style='margin:0'><img id='preview' src='" + url + "'></body>");
                page.waitForFunction("document.getElementById('preview').complete");
                assertEquals(500, ((Number) page.evaluate("document.getElementById('preview').naturalWidth")).intValue());
                var screenshot = svgFile.resolveSibling(svgFile.getFileName() + ".png");
                page.locator("#preview").screenshot(new Locator.ScreenshotOptions().setPath(screenshot));
                var image = ImageIO.read(screenshot.toFile());
                var background = new Color(image.getRGB(5, 5));
                assertEquals(new Color(237, 243, 251), background,
                        "Card background must survive SVG image embedding");
                int darkPixels = 0, rightmostDark = 0;
                for (int y = 160; y < Math.min(330, image.getHeight()); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        var color = new Color(image.getRGB(x, y));
                        if (color.getRed() < 100 && color.getGreen() < 120 && color.getBlue() < 150) {
                            darkPixels++;
                            rightmostDark = Math.max(rightmostDark, x);
                        }
                    }
                }
                assertTrue(darkPixels > 100 && rightmostDark > 200,
                        "Rendered content must retain its color and horizontal positioning");
                page.navigate(url);
                assertEquals("rect(1px, 1px, 1px, 1px)",
                        page.locator("mjx-assistive-mml").first().evaluate("element => getComputedStyle(element).clip"),
                        "Accessibility MathML must remain clipped rather than duplicate the vector formula");
                var direct = ImageIO.read(new ByteArrayInputStream(page.screenshot()));
                var directBackground = new Color(direct.getRGB(5, 5));
                assertEquals(new Color(237, 243, 251), directBackground,
                        "CSP must preserve styled SVG when opened directly");
            }
        } finally {
            server.stop(0);
        }
    }
}
