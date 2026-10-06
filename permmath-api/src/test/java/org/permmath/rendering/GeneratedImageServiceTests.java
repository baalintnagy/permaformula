package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.permmath.PermMathProperties;
import org.permmath.rendering.cache.CaffeineImageStore;
import org.permmath.rendering.cache.ImageCacheProperties;
import org.permmath.url.ImageDescriptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

class GeneratedImageServiceTests {
    private final CaffeineImageStore store = new CaffeineImageStore(
            new ImageCacheProperties(DataSize.ofMegabytes(1), Duration.ofHours(1)));
    private final ImageRendererService html = mock(ImageRendererService.class);
    private final FormulaSvgRenderer formula = mock(FormulaSvgRenderer.class);

    @Test void rejectsComplexInputBeforeCallingEitherRenderer() throws Exception {
        var service = service(800);
        assertThrows(IllegalArgumentException.class, () -> service.getImg("x".repeat(262145)));
        assertThrows(IllegalArgumentException.class, () -> service.getImage(new ImageDescriptor("<div>".repeat(129), null, null, null)));
        verifyNoInteractions(html, formula);
    }

    @Test
    void htmlUsesEffectiveWidthAndCachesReusableContent() throws Exception {
        when(html.getImage(any())).thenReturn(resource("<svg xmlns=\"http://www.w3.org/2000/svg\">α</svg>"));
        var service = service(800);
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">α</svg>", service.getImage(new ImageDescriptor("html", null, null, null)));
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">α</svg>", service.getImage(new ImageDescriptor("html", 800, List.of(), "")));
        verify(html, times(1)).getImage(any());
    }

    @Test
    void formulaCachesExactContentAndDoesNotCollideWithHtml() throws Exception {
        when(formula.render("x")).thenReturn("<svg xmlns=\"http://www.w3.org/2000/svg\">formula-svg</svg>");
        when(html.getImage(any())).thenReturn(resource("<svg xmlns=\"http://www.w3.org/2000/svg\">html-svg</svg>"));
        var service = service(800);
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">formula-svg</svg>", service.getImg("x"));
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">formula-svg</svg>", service.getImg("x"));
        assertEquals("<svg xmlns=\"http://www.w3.org/2000/svg\">html-svg</svg>", service.getImage(new ImageDescriptor("x", null, null, null)));
        verify(formula, times(1)).render("x");
    }

    @Test
    void outputAffectingSettingsHaveDifferentKeys() throws Exception {
        when(html.getImage(any())).thenAnswer(_ -> resource("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        var descriptor = new ImageDescriptor("html", null, null, null);
        service(800).getImage(descriptor);
        service(900).getImage(descriptor);
        service(800).getImage(new ImageDescriptor("html", null, null, "red"));
        service(800).getImage(new ImageDescriptor("html", null, List.of("a.css", "b.css"), null));
        service(800).getImage(new ImageDescriptor("html", null, List.of("b.css", "a.css"), null));
        verify(html, times(5)).getImage(any());
    }

    @Test
    void contentKeysAreStableAcrossServiceInstances() throws Exception {
        when(html.getImage(any())).thenReturn(resource("<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"));
        var descriptor = new ImageDescriptor("html", null, null, null);
        service(800).getImage(descriptor);
        service(800).getImage(descriptor);
        verify(html, times(1)).getImage(any());
    }

    @Test
    void rejectsForgedOutputFromEitherRendererBeforeCaching() throws Exception {
        String attack = "<svg xmlns='http://www.w3.org/2000/svg'><a href='javascript:alert(1)'/></svg>";
        when(html.getImage(any())).thenAnswer(_ -> resource(attack));
        when(formula.render("x")).thenReturn(attack);
        var service = service(800);
        var descriptor = new ImageDescriptor("x", null, null, null);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(java.io.IOException.class, () -> service.getImage(descriptor));
            assertThrows(java.io.IOException.class, () -> service.getImg("x"));
        }
        verify(html, times(2)).getImage(any());
        verify(formula, times(2)).render("x");
    }

    @Test
    void cachesOnlyFilteredPresentationStyles() throws Exception {
        String rendered = "<svg xmlns='http://www.w3.org/2000/svg'><g style='fill:currentColor;"
                + "background-image:url(https://evil.example/a)'/></svg>";
        when(formula.render("x")).thenReturn(rendered);
        var service = service(800);
        var clean = service.getImg("x");
        assertFalse(clean.contains("evil.example"));
        assertTrue(clean.contains("fill:currentColor"));
        assertEquals(clean, service.getImg("x"));
        verify(formula, times(1)).render("x");
    }

    private GeneratedImageService service(int width) {
        return new GeneratedImageService(store, html, formula, new PermMathProperties("http://localhost", width, 2000),
                JsonMapper.builder().build());
    }

    @Test
    void cachedImageRemainsAvailableWhileGenerationCapacityIsOccupied() throws Exception {
        var work = new ApiWorkService(new ApiWorkProperties(1, 1, 1, Duration.ofSeconds(5)));
        var service = service(800);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "work", work);
        when(formula.render("cached")).thenReturn("<svg xmlns='http://www.w3.org/2000/svg'><text>cached</text></svg>");
        String cached = service.getImg("cached");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var caller = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var active = caller.submit(() -> work.generate(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
                return "done";
            }));
            try {
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(cached, service.getImg("cached"));
                assertEquals(503, assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> service.getImg("uncached")).getStatusCode().value());
                verify(formula, never()).render("uncached");
            } finally { release.countDown(); }
            active.get(2, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static InputStreamResource resource(String svg) {
        return new InputStreamResource(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }
}
