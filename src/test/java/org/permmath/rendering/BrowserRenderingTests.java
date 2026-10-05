package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.permmath.PermMathProperties;
import org.permmath.url.ImageDescriptor;

@EnabledIfSystemProperty(named = "rendering.tests", matches = "true")
class BrowserRenderingTests {
    @ParameterizedTest
    @EnumSource(RenderingProperties.Engine.class)
    void rendersOriginalStyledHtmlExample(RenderingProperties.Engine engine) throws Exception {
        String example = Files.readString(Path.of("requests.md"));
        var html = Pattern.compile("```html\\s*(.*?)```", Pattern.DOTALL).matcher(example);
        var css = Pattern.compile("```css\\s*(.*?)```", Pattern.DOTALL).matcher(example);
        assertTrue(html.find());
        assertTrue(css.find());
        var settings = new RenderingProperties(engine, Path.of(".runtime/browsers"), Duration.ofSeconds(30));
        try (var renderer = new ImageRendererService(
                new PermMathProperties("http://localhost:8086", 800, 2000), settings)) {
            var image = renderer.getImage(new ImageDescriptor(html.group(1), 1000, List.of(), css.group(1)));
            var directory = Path.of("target/rendering");
            Files.createDirectories(directory);
            var destination = directory.resolve(engine.name().toLowerCase() + ".svg");
            try (var input = image.getInputStream()) { Files.write(destination, input.readAllBytes()); }
            var decoded = parse(Files.readString(destination));
            assertEquals("svg", decoded.getDocumentElement().getLocalName());
            assertEquals("1000", decoded.getDocumentElement().getAttribute("width"));
            assertTrue(Integer.parseInt(decoded.getDocumentElement().getAttribute("height")) > 100);
            assertTrue(decoded.getElementsByTagNameNS("http://www.w3.org/2000/svg", "foreignObject").getLength() > 0);
            assertTrue(decoded.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").getLength() > 0);
            assertTrue(Files.readString(destination).contains("background-color:rgb(48, 48, 48)"));
            verifySvgImagePreview(destination);
        }
    }

    @ParameterizedTest
    @EnumSource(RenderingProperties.Engine.class)
    void plainHtmlAndExternalStylesRenderWithoutLeakingBetweenRequests(RenderingProperties.Engine engine) throws Exception {
        var cssServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        cssServer.createContext("/style.css", exchange -> {
            var css = "body {margin:0; background:rgb(180,30,30);} div {height:150px;}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/css");
            exchange.sendResponseHeaders(200, css.length);
            exchange.getResponseBody().write(css);
            exchange.close();
        });
        cssServer.start();
        var settings = new RenderingProperties(engine, Path.of(".runtime/browsers"), Duration.ofSeconds(30));
        try (var renderer = new ImageRendererService(
                new PermMathProperties("http://localhost:8086", 800, 2000), settings)) {
            String ref = "http://127.0.0.1:" + cssServer.getAddress().getPort() + "/style.css";
            var first = renderer.getImage(new ImageDescriptor("<div>Plain HTML</div>", 320, List.of(ref), null));
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
        } finally { cssServer.stop(0); }
    }
    private static org.w3c.dom.Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(svg)));
    }

    private static void verifySvgImagePreview(Path svgFile) throws Exception {
        var browsers = Path.of(".runtime/browsers").toAbsolutePath();
        try (var playwright = com.microsoft.playwright.Playwright.create(
                new com.microsoft.playwright.Playwright.CreateOptions().setEnv(java.util.Map.of(
                        "PLAYWRIGHT_BROWSERS_PATH", browsers.toString(), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            try (var browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions()
                    .setExecutablePath(Path.of(playwright.chromium().executablePath())).setHeadless(true))) {
                var page = browser.newPage();
                page.setViewportSize(1000, 800);
                String data = java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(svgFile));
                page.setContent("<body style='margin:0'><img id='preview' src='data:image/svg+xml;base64," + data + "'></body>");
                page.waitForFunction("document.getElementById('preview').complete");
                assertEquals(1000, ((Number) page.evaluate("document.getElementById('preview').naturalWidth")).intValue());
                var screenshot = svgFile.resolveSibling(svgFile.getFileName() + ".png");
                page.locator("#preview").screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(screenshot));
                var image = javax.imageio.ImageIO.read(screenshot.toFile());
                var background = new java.awt.Color(image.getRGB(5, 5));
                assertTrue(background.getRed() < 80 && background.getGreen() < 80 && background.getBlue() < 80);
                int redMath = 0, cyanMath = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        var color = new java.awt.Color(image.getRGB(x, y));
                        if (color.getRed() > 150 && color.getRed() > color.getGreen() * 1.3
                                && color.getRed() > color.getBlue() * 1.3) redMath++;
                        if (color.getGreen() > 150 && color.getBlue() > 150 && color.getRed() < 120) cyanMath++;
                    }
                }
                assertTrue(redMath > 10 && cyanMath > 10, "Formula colors must survive SVG image embedding");
            }
        }
    }
}
