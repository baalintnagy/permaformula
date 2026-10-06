package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Test;
import org.permmath.PermMathProperties;
import org.permmath.url.ImageDescriptor;

@EnabledIfSystemProperty(named = "rendering.tests", matches = "true")
class BrowserRenderingTests {
    @Test
    void mathJaxSafeModeBlocksGeneratedActiveLinksAndRetainsSafeMath() throws Exception {
        try (var renderer = imageRenderer(new PermMathProperties("http://localhost:8086", 800, 2000),
                new BrowserSettings(Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
            String html = "<p>\\(\\href{javascript:alert(1)}{probe}\\)</p>"
                    + "<p>\\(\\href{file:///etc/passwd}{file}\\)</p>"
                    + "<p>\\(\\href{data:text/html,evil}{data}\\)</p>"
                    + "<p>\\(\\href{https://example.com/math}{safe}\\)</p><p>`sqrt(x^2)`</p>";
            try (var input = renderer.getImage(new ImageDescriptor(html, 320, null, null)).getInputStream()) {
                String raw = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var tripwire = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
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
                    + "<script>window.renderSvg='FORGED';window.renderState='ready';fetch('" + target + "')</script>"
                    + "<img src='" + target + "' onerror='window.renderSvg=\"FORGED\"'>"
                    + "<iframe src='" + target + "'></iframe>";
            try (var image = renderer.getImage(new ImageDescriptor(input, 320, null,
                    "@import url('" + target + "'); div {color:red;background-image:url('" + target + "')}"))
                    .getInputStream()) {
                var svg = new String(image.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertEquals("svg", parse(svg).getDocumentElement().getLocalName());
                assertTrue(svg.contains("Safe"));
                assertFalse(svg.contains("FORGED"));
                assertFalse(svg.contains("onclick"));
                assertEquals(0, hits.get());
            }
        } finally { tripwire.stop(0); }
    }

    @Test
    void rendersOriginalStyledHtmlExample() throws Exception {
        var method = org.permmath.PermMathController.class.getMethod("getUrl", String.class,
                Integer.class, List.class, String.class);
        var parameters = method.getParameters();
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var refs = mapper.readValue(parameters[2].getAnnotation(io.swagger.v3.oas.annotations.Parameter.class).example(),
                new tools.jackson.core.type.TypeReference<List<String>>() {});
        var example = new ImageDescriptor(
                parameters[0].getAnnotation(io.swagger.v3.oas.annotations.Parameter.class).example(), null, refs,
                parameters[3].getAnnotation(io.swagger.v3.oas.annotations.Parameter.class).example());
        var settings = new BrowserSettings(Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false);
        try (var renderer = imageRenderer(
                new PermMathProperties("http://localhost:8086", 800, 2000), settings)) {
            var image = renderer.getImage(new ImageDescriptor(example.forumla(), 1000, example.styleRefs(), example.style()));
            var directory = Path.of("target/rendering");
            Files.createDirectories(directory);
            var destination = directory.resolve("chromium.svg");
            try (var input = image.getInputStream()) {
                String raw = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                Files.writeString(directory.resolve("chromium-raw.svg"), raw);
                Files.writeString(destination, SvgOutputPolicy.sanitize(raw));
            }
            var decoded = parse(Files.readString(destination));
            assertEquals("svg", decoded.getDocumentElement().getLocalName());
            assertEquals("1000", decoded.getDocumentElement().getAttribute("width"));
            assertTrue(Integer.parseInt(decoded.getDocumentElement().getAttribute("height")) > 100);
            assertTrue(decoded.getElementsByTagNameNS("http://www.w3.org/2000/svg", "foreignObject").getLength() > 0);
            assertTrue(decoded.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").getLength() > 0);
            assertTrue(Files.readString(destination).contains("background-color:rgb(48,48,48)"));
            assertTrue(Files.readString(destination).contains("color:rgb(248,249,250)"),
                    "Title must use the external Bootstrap light color");
            assertTrue(Files.readString(destination).contains("color:rgb(108,117,125)"),
                    "Paragraph must use the external Bootstrap secondary color");
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
                var svg = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                var image = parse(svg).getDocumentElement();
                assertEquals("320", image.getAttribute("width"));
                assertEquals("150", image.getAttribute("height"));
                assertTrue(svg.contains("background-color:rgb(180, 30, 30)"));
            }
            var second = renderer.getImage(new ImageDescriptor("<div>New request</div>", 320, null,
                    "body {margin:0; background:rgb(30,30,180);} div {height:180px;}"));
            try (var input = second.getInputStream()) {
                var svg = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                var image = parse(svg).getDocumentElement();
                assertEquals("180", image.getAttribute("height"));
                assertTrue(svg.contains("background-color:rgb(30, 30, 180)"));
                assertFalse(svg.contains("<script"));
            }
        }
    }
    private static ImageRendererService imageRenderer(PermMathProperties properties, BrowserSettings settings) throws Exception {
        return new ImageRendererService(properties, new RenderGateway() {
            public byte[] renderHtml(String document, int width) throws java.io.IOException {
                try (var assets = new RenderAssets(); var chromium = new ChromiumRenderer(settings)) {
                    return chromium.render(assets.add(document), width);
                }
            }
            public byte[] renderFormula(String formula) throws java.io.IOException {
                try (var renderer = new AsciiMathRenderer()) {
                    return renderer.render(formula).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        });
    }
    private static org.w3c.dom.Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(svg)));
    }

    private static void verifySvgImagePreview(Path svgFile) throws Exception {
        var browsers = Path.of("../.runtime/browsers").toAbsolutePath();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        byte[] svg = Files.readAllBytes(svgFile);
        server.createContext("/preview.svg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.getResponseHeaders().set("Content-Security-Policy", SvgOutputPolicy.CSP);
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.sendResponseHeaders(200, svg.length);
            try (var body = exchange.getResponseBody()) { body.write(svg); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/preview.svg";
        try (var playwright = com.microsoft.playwright.Playwright.create(
                new com.microsoft.playwright.Playwright.CreateOptions().setEnv(java.util.Map.of(
                        "PLAYWRIGHT_BROWSERS_PATH", browsers.toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            try (var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions()
                    .setExecutablePath(Path.of(playwright.chromium().executablePath())).setHeadless(true))) {
                var page = browser.newPage();
                page.setViewportSize(1000, 800);
                page.setContent("<body style='margin:0'><img id='preview' src='" + url + "'></body>");
                page.waitForFunction("document.getElementById('preview').complete");
                assertEquals(1000, ((Number) page.evaluate("document.getElementById('preview').naturalWidth")).intValue());
                var screenshot = svgFile.resolveSibling(svgFile.getFileName() + ".png");
                page.locator("#preview").screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(screenshot));
                var image = javax.imageio.ImageIO.read(screenshot.toFile());
                var background = new java.awt.Color(image.getRGB(5, 5));
                assertTrue(background.getRed() < 80 && background.getGreen() < 80 && background.getBlue() < 80);
                int redMath = 0, cyanMath = 0;
                int rightmostRed = 0, rightmostCyan = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        var color = new java.awt.Color(image.getRGB(x, y));
                        if (color.getRed() > 150 && color.getRed() > color.getGreen() * 1.3
                                && color.getRed() > color.getBlue() * 1.3) {
                            redMath++;
                            rightmostRed = Math.max(rightmostRed, x);
                        }
                        if (color.getGreen() > 150 && color.getBlue() > 150 && color.getRed() < 120) {
                            cyanMath++;
                            rightmostCyan = Math.max(rightmostCyan, x);
                        }
                    }
                }
                assertTrue(redMath > 10 && cyanMath > 10, "Formula colors must survive SVG image embedding");
                assertTrue(rightmostRed > 300 && rightmostCyan > 250,
                        "Formula glyphs must retain their horizontal positioning, rather than overlap at the origin");
                page.navigate(url);
                assertEquals("rect(1px, 1px, 1px, 1px)",
                        page.locator("mjx-assistive-mml").first().evaluate("element => getComputedStyle(element).clip"),
                        "Accessibility MathML must remain clipped rather than duplicate the vector formula");
                var direct = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(page.screenshot()));
                var directBackground = new java.awt.Color(direct.getRGB(5, 5));
                assertTrue(directBackground.getRed() < 80 && directBackground.getGreen() < 80
                        && directBackground.getBlue() < 80, "CSP must preserve styled SVG when opened directly");
            }
        } finally {
            server.stop(0);
        }
    }
}
