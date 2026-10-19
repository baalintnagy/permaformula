package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.permaformula.contract.v1.*;

/** Opt-in end-to-end measurements, including process startup; no cache can hide rendering. */
@EnabledIfSystemProperty(named = "rendering.tests", matches = "true")
class WarmRenderingTests {
    @Test
    void jpegResolutionStaysWithinConfiguredPixelBudget() throws Exception {
        var policy = org.permaformula.contract.ComplexityPolicy.load(new java.util.Properties(),
            java.util.Map.of("PERMAFORMULA_COMPLEXITY_RENDER_AREA", "2500"));
        try (var assets = new RenderAssets();
            var renderer = new ChromiumRenderer(new BrowserSettings(
                Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false), policy)) {
            byte[] jpeg = renderer.jpeg(assets.add("<!doctype html><html><body></body></html>"),
                """
                    <svg xmlns='http://www.w3.org/2000/svg' width='40' height='20'><rect \
                    width='40' height='20' fill='blue'/></svg>\
                    """);
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
            assertTrue(image.getWidth() > 40
                && image.getWidth() < 80);
            assertTrue((long) image.getWidth() * image.getHeight() <= 2500);
        }
    }

    @Test
    void jpegUsesSourceSvgIncludingForeignObjectAndWhiteTransparency() throws Exception {
        try (var supervisor = new SupervisedRenderer(
            new BrowserSettings(Path.of("../.runtime/browsers"),
                Duration.ofSeconds(30), false),
            1)) {
            String svg = """
                <svg xmlns='http://www.w3.org/2000/svg' width='100' height='50'><foreignObject \
                width='50' height='50'><div xmlns='http://www.w3.org/1999/xhtml' \
                style='background:rgb(255,0,0);width:50px;height:50px'>Text</div></foreignObject></svg>\
                """;
            var request = RenderRequest.newBuilder().setRequestId("jpeg").setJpegSvg(svg).build();
            byte[] jpeg = supervisor.execute(request, Duration.ofSeconds(30)).getSvg()
                .toByteArray();
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
            assertEquals(200, image.getWidth());
            assertEquals(100, image.getHeight());
            var red = new java.awt.Color(image.getRGB(50, 70));
            assertTrue(red.getRed() > 240
                && red.getGreen() < 20
                && red.getBlue() < 20);
            var white = new java.awt.Color(image.getRGB(150, 70));
            assertTrue(white.getRed() > 240
                && white.getGreen() > 240
                && white.getBlue() > 240);
            var formula = supervisor
                .execute(SupervisedRendererTests.request(), Duration.ofSeconds(30)).getSvg()
                .toStringUtf8();
            var formulaJpeg = supervisor.execute(request.toBuilder().setJpegSvg(formula).build(),
                Duration.ofSeconds(30));
            assertNotNull(javax.imageio.ImageIO
                .read(new java.io.ByteArrayInputStream(formulaJpeg.getSvg().toByteArray())));
            java.nio.file.Files.createDirectories(Path.of("target/rendering"));
            java.nio.file.Files.write(Path.of("target/rendering/foreign-object.jpg"), jpeg);
            java.nio.file.Files.write(Path.of("target/rendering/formula.jpg"),
                formulaJpeg.getSvg().toByteArray());
        }
    }

    @Test
    void productionSupervisorRendersBothKindsWithRestrictedWorkerEnvironment() throws Exception {
        try (var supervisor = new SupervisedRenderer(
            new BrowserSettings(Path.of("../.runtime/browsers"),
                Duration.ofSeconds(30), false),
            1)) {
            assertTrue(supervisor.execute(SupervisedRendererTests.request(), Duration.ofSeconds(30))
                .getSvg().toStringUtf8().contains("<svg"));
            assertTrue(
                supervisor.execute(html("html", "<p>Production pool</p>"), Duration.ofSeconds(30))
                    .getSvg().toStringUtf8().contains("Production pool"));
        }
    }

    private static ProcessBuilder worker(String kind, String main) {
        var command = new java.util.ArrayList<String>(java.util.List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            main));
        if (main.equals(RenderWorkerMain.class.getName())) {
            command.add(kind);
        }
        command.addAll(java.util.List
            .of(Path.of("../.runtime/browsers").toAbsolutePath().toString(), "30000", "false"));
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    @Test
    void comparesColdAndWarmFormulaRendering() throws Exception {
        var request = SupervisedRendererTests.request();
        long started = System.nanoTime();
        var cold = SupervisedRenderer.run(worker("formula", RenderJobMain.class.getName()), request,
            Duration.ofSeconds(30));
        long coldMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        try (var pool = new RenderWorkerPool(1,
            () -> worker("formula", RenderWorkerMain.class.getName()), Duration.ofSeconds(60),
            100)) {
            var first = pool.execute(request, Duration.ofSeconds(30));
            assertTrue(first.getSvg().toStringUtf8().contains("<svg"));
            for (String formula : new String[] { "x", "sqrt(x)", "sum_(i=1)^n i" }) {
                started = System.nanoTime();
                var output = pool.execute(
                    request.toBuilder()
                        .setFormula(AsciiMathFormula.newBuilder().setFormula(formula)).build(),
                    Duration.ofSeconds(30));
                assertTrue(output.getSvg().toStringUtf8().contains("<svg"));
                System.err
                    .println("""
                        event=render_benchmark kind=formula coldMs=%s warmMs=%s\
                        """.formatted(coldMs,
                        Duration.ofNanos(System.nanoTime() - started).toMillis()));
            }
            assertFalse(cold.getSvg().isEmpty());
        }
    }

    private static RenderRequest html(String id, String body) {
        String document = """
            <!doctype html><html><head><script src='/export-svg.js'></script><script \
            id='mathjax-script' defer \
            src='/mathjax/tex-svg.js'></script></head><body>%s</body></html>\
            """.formatted(body);
        return RenderRequest.newBuilder().setRequestId(id)
            .setHtml(HtmlDocument.newBuilder().setWidth(320).setDocument(document)).build();
    }

    @Test
    void reusesChromiumWithoutBrowserStateOrDocumentsLeaking() throws Exception {
        var first = html("first",
            """
                <script>localStorage.setItem('secret','leaked');globalThis.secret='leaked';</script><p>First image</p>\
                """);
        var second = html("second",
            """
                <p>Second image</p><script>if(localStorage.getItem('secret')||globalThis.secret)document.body.append('LEAKED_STATE');</script>\
                """);
        long started = System.nanoTime();
        assertFalse(SupervisedRenderer
            .run(worker("html", RenderJobMain.class.getName()), first, Duration.ofSeconds(30))
            .getSvg().isEmpty());
        long coldMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        try (var pool = new RenderWorkerPool(1,
            () -> worker("html", RenderWorkerMain.class.getName()), Duration.ofSeconds(60), 100)) {
            assertTrue(pool.execute(first, Duration.ofSeconds(30)).getSvg().toStringUtf8()
                .contains("First image"));
            started = System.nanoTime();
            String svg = pool.execute(second, Duration.ofSeconds(30)).getSvg().toStringUtf8();
            assertTrue(svg.contains("Second image"));
            assertFalse(svg.contains("First image"));
            assertFalse(svg.contains("LEAKED_STATE"));
            System.err.println("""
                event=render_benchmark kind=html coldMs=%s warmMs=%s\
                """.formatted(coldMs, Duration.ofNanos(System.nanoTime() - started).toMillis()));
        }
    }
}
