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

    @Test
    void htmlUsesEffectiveWidthAndCachesReusableContent() throws Exception {
        when(html.getImage(any())).thenReturn(resource("<svg>α</svg>"));
        var service = service(800);
        assertEquals("<svg>α</svg>", service.getImage(new ImageDescriptor("html", null, null, null)));
        assertEquals("<svg>α</svg>", service.getImage(new ImageDescriptor("html", 800, List.of(), "")));
        verify(html, times(1)).getImage(any());
    }

    @Test
    void formulaCachesExactContentAndDoesNotCollideWithHtml() throws Exception {
        when(formula.render("x")).thenReturn("formula-svg");
        when(html.getImage(any())).thenReturn(resource("html-svg"));
        var service = service(800);
        assertEquals("formula-svg", service.getImg("x"));
        assertEquals("formula-svg", service.getImg("x"));
        assertEquals("html-svg", service.getImage(new ImageDescriptor("x", null, null, null)));
        verify(formula, times(1)).render("x");
    }

    @Test
    void outputAffectingSettingsHaveDifferentKeys() throws Exception {
        when(html.getImage(any())).thenAnswer(_ -> resource("<svg/>"));
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
        when(html.getImage(any())).thenReturn(resource("<svg/>"));
        var descriptor = new ImageDescriptor("html", null, null, null);
        service(800).getImage(descriptor);
        service(800).getImage(descriptor);
        verify(html, times(1)).getImage(any());
    }

    private GeneratedImageService service(int width) {
        return new GeneratedImageService(store, html, formula, new PermMathProperties("http://localhost", width, 2000),
                JsonMapper.builder().build());
    }

    private static InputStreamResource resource(String svg) {
        return new InputStreamResource(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }
}
