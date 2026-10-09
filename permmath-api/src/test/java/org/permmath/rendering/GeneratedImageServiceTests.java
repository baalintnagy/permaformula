package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.permmath.PermMathProperties;
import org.permmath.rendering.cache.CaffeineImageStore;
import org.permmath.rendering.cache.ImageCacheProperties;
import org.permmath.url.ImageDescriptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;

class GeneratedImageServiceTests {
    private final CaffeineImageStore store = new CaffeineImageStore(
            new ImageCacheProperties(DataSize.ofMegabytes(1), Duration.ofHours(1)));
    private final ImageRendererService html = mock(ImageRendererService.class);
    private final FormulaSvgRenderer formula = mock(FormulaSvgRenderer.class);

    @Test
    void concurrentJpegRequestsShareBothSourceAndConversion() throws Exception {
        when(formula.render("shared-jpg")).thenReturn("<svg xmlns='http://www.w3.org/2000/svg' width='20' height='10'/>");
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(40, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", output);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var gateway = mock(RenderGateway.class);
        when(gateway.renderJpeg(any())).thenAnswer(_ -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return output.toByteArray();
        });
        var service = service(500);
        ReflectionTestUtils.setField(service, "gateway", gateway);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.getExpJpg("shared-jpg"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> service.getExpJpg("shared-jpg"));
            release.countDown();
            assertArrayEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        verify(formula, times(1)).render("shared-jpg");
        verify(gateway, times(1)).renderJpeg(any());
    }

    @Test
    void jpegFirstPopulatesSvgAndCachesConversionAcrossInstances() throws Exception {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='20' height='10'><path d='M0 0'/></svg>";
        when(formula.render("x")).thenReturn(svg);
        when(html.getImage(any())).thenAnswer(_ -> resource(svg));
        var gateway = mock(RenderGateway.class);
        var image = new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", output);
        when(gateway.renderJpeg(any())).thenReturn(output.toByteArray());
        var service = service(500);
        ReflectionTestUtils.setField(service, "gateway", gateway);
        assertArrayEquals(output.toByteArray(), service.getExpJpg("x"));
        service.getExp("x");
        service.getExpJpg("x");
        var restarted = service(500);
        ReflectionTestUtils.setField(restarted, "gateway", gateway);
        restarted.getExpJpg("x");
        restarted.getHtmlJpg(new ImageDescriptor("html", null, null, null));
        service.getHtml(new ImageDescriptor("html", null, null, null));
        verify(formula, times(1)).render("x");
        verify(html, times(1)).getImage(any());
        verify(gateway, times(1)).renderJpeg(any());
    }

    @Test
    void failedJpegConversionKeepsSourceSvgAndRetriesConversion() throws Exception {
        when(formula.render("x")).thenReturn("<svg xmlns='http://www.w3.org/2000/svg' width='20' height='10'/>");
        var gateway = mock(RenderGateway.class);
        when(gateway.renderJpeg(any())).thenReturn(new byte[] { 1, 2, 3 });
        var service = service(500);
        ReflectionTestUtils.setField(service, "gateway", gateway);
        assertThrows(IOException.class, () -> service.getExpJpg("x"));
        assertThrows(IOException.class, () -> service.getExpJpg("x"));
        verify(formula, times(1)).render("x");
        verify(gateway, times(2)).renderJpeg(any());
    }

    @Test
    void rejectsComplexInputBeforeCallingEitherRenderer() throws Exception {
        var service = service(500);
        assertThrows(IllegalArgumentException.class, () -> service.getExp("x".repeat(262145)));
        assertThrows(IllegalArgumentException.class, () -> service.getHtml(new ImageDescriptor("<div>".repeat(129), null, null, null)));
        verifyNoInteractions(html, formula);
    }

    @Test
    void htmlUsesEffectiveWidthAndCachesReusableContent() throws Exception {
        when(html.getImage(any())).thenReturn(resource("<svg xmlns=\"http://www.w3.org/2000/svg\">α</svg>"));
        var service = service(500);
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">α</svg>", service.getHtml(new ImageDescriptor("html", null, null, null)));
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">α</svg>", service.getHtml(new ImageDescriptor("html", 500, List.of(), "")));
        verify(html, times(1)).getImage(any());
    }

    @Test
    void formulaCachesExactContentAndDoesNotCollideWithHtml() throws Exception {
        when(formula.render("x")).thenReturn("<svg xmlns=\"http://www.w3.org/2000/svg\">formula-svg</svg>");
        when(html.getImage(any())).thenReturn(resource("<svg xmlns=\"http://www.w3.org/2000/svg\">html-svg</svg>"));
        var service = service(500);
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">formula-svg</svg>", service.getExp("x"));
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">formula-svg</svg>", service.getExp("x"));
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">html-svg</svg>", service.getHtml(new ImageDescriptor("x", null, null, null)));
        verify(formula, times(1)).render("x");
    }

    @Test
    void outputAffectingSettingsHaveDifferentKeys() throws Exception {
        when(html.getImage(any())).thenAnswer(_ -> resource("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        var descriptor = new ImageDescriptor("html", null, null, null);
        service(500).getHtml(descriptor);
        service(900).getHtml(descriptor);
        service(500).getHtml(new ImageDescriptor("html", null, null, "red"));
        service(500).getHtml(new ImageDescriptor("html", null, List.of("a.css", "b.css"), null));
        service(500).getHtml(new ImageDescriptor("html", null, List.of("b.css", "a.css"), null));
        verify(html, times(5)).getImage(any());
    }

    @Test
    void contentKeysAreStableAcrossServiceInstances() throws Exception {
        when(html.getImage(any())).thenReturn(resource("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        var descriptor = new ImageDescriptor("html", null, null, null);
        service(500).getHtml(descriptor);
        service(500).getHtml(descriptor);
        verify(html, times(1)).getImage(any());
    }

    @Test
    void rejectsForgedOutputFromEitherRendererBeforeCaching() throws Exception {
        String attack = "<svg xmlns='http://www.w3.org/2000/svg'><a href='javascript:alert(1)'/></svg>";
        when(html.getImage(any())).thenAnswer(_ -> resource(attack));
        when(formula.render("x")).thenReturn(attack);
        var service = service(500);
        var descriptor = new ImageDescriptor("x", null, null, null);
        IntStream.range(0, 2).forEach(_ -> {
            assertThrows(IOException.class, () -> service.getHtml(descriptor));
            assertThrows(IOException.class, () -> service.getExp("x"));
        });
        verify(html, times(2)).getImage(any());
        verify(formula, times(2)).render("x");
    }

    @Test
    void cachesOnlyFilteredPresentationStyles() throws Exception {
        String rendered = "<svg xmlns='http://www.w3.org/2000/svg'><g style='fill:currentColor;"
                + "background-image:url(https://evil.example/a)'/></svg>";
        when(formula.render("x")).thenReturn(rendered);
        var service = service(500);
        var clean = service.getExp("x");
        assertFalse(clean.contains("evil.example"));
        assertTrue(clean.contains("fill:currentColor"));
        assertEquals(clean, service.getExp("x"));
        verify(formula, times(1)).render("x");
    }

    private GeneratedImageService service(int width) {
        return new GeneratedImageService(store, html, formula, new PermMathProperties("http://localhost", width, 2000),
                JsonMapper.builder().build());
    }

    @Test
    void cachedImageRemainsAvailableWhileGenerationCapacityIsOccupied() throws Exception {
        var work = new ApiWorkService(new ApiWorkProperties(1, 1, 1, Duration.ofSeconds(5)));
        var service = service(500);
        ReflectionTestUtils.setField(service, "work", work);
        when(formula.render("cached")).thenReturn("<svg xmlns='http://www.w3.org/2000/svg'><text>cached</text></svg>");
        String cached = service.getExp("cached");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var active = caller.submit(() -> work.generate(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    throw new IOException(interrupted);
                }
                return "done";
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(cached, service.getExp("cached"));
                assertEquals(503, assertThrows(ResponseStatusException.class,
                        () -> service.getExp("uncached")).getStatusCode().value());
                verify(formula, never()).render("uncached");
            } finally {
                release.countDown();
            }
            active.get(2, TimeUnit.SECONDS);
        }
    }

    private static InputStreamResource resource(String svg) {
        return new InputStreamResource(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }
}
