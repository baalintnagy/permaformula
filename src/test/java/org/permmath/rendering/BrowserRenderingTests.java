package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
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
            var destination = directory.resolve(engine.name().toLowerCase() + ".jpg");
            try (var input = image.getInputStream()) { Files.write(destination, input.readAllBytes()); }
            var decoded = ImageIO.read(destination.toFile());
            assertNotNull(decoded);
            assertEquals(1000, decoded.getWidth());
            assertTrue(decoded.getHeight() > 100);
            var pixel = new java.awt.Color(decoded.getRGB(5, 5));
            assertTrue(pixel.getRed() < 80 && pixel.getGreen() < 80 && pixel.getBlue() < 80,
                    "The supplied dark background should be rendered");
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
                var image = ImageIO.read(input);
                assertEquals(320, image.getWidth());
                assertEquals(150, image.getHeight());
                assertTrue(new java.awt.Color(image.getRGB(100, 100)).getRed() > 150);
            }
            var second = renderer.getImage(new ImageDescriptor("<div>New request</div>", 320, null,
                    "body {margin:0; background:rgb(30,30,180);} div {height:180px;}"));
            try (var input = second.getInputStream()) {
                var image = ImageIO.read(input);
                assertEquals(180, image.getHeight());
                var color = new java.awt.Color(image.getRGB(100, 100));
                assertTrue(color.getBlue() > 150 && color.getRed() < 60);
            }
        } finally { cssServer.stop(0); }
    }
}
