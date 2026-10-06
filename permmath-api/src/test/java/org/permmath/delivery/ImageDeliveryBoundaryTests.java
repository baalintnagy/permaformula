package org.permmath.delivery;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.permmath.rendering.FormulaSvgRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"permmath.host=https://math.example.com",
        "permmath.image-host=https://math-images.example.net", "permmath.image-delivery.required=true",
        "permmath.rate-limit.trusted-proxies=172.30.86.2"})
@AutoConfigureMockMvc
class ImageDeliveryBoundaryTests {
    @Autowired MockMvc mvc;
    @MockitoBean FormulaSvgRenderer renderer;

    private static MockHttpServletRequestBuilder peer(String path, String ip) {
        return get(java.net.URI.create(path)).with(request -> { request.setRemoteAddr(ip); return request; });
    }

    @Test void redirectsUntrustedOrUnmarkedHandlersBeforeRenderingAndPreservesRawQuery() throws Exception {
        for (var request : java.util.List.of(
                peer("/img?formula=x%2By", "203.0.113.50").header("X-Permmath-Image-Delivery", "1"),
                peer("/img?formula=x%2By", "172.30.86.2").header("X-Forwarded-For", "203.0.113.51"))) {
            mvc.perform(request).andExpect(status().isTemporaryRedirect())
                    .andExpect(header().string("Location", "https://math-images.example.net/img?formula=x%2By"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(peer("/image?ubcq=malformed", "203.0.113.52"))
                .andExpect(status().isTemporaryRedirect());
        mvc.perform(peer("/img;ignored?formula=x", "203.0.113.53"))
                .andExpect(status().isTemporaryRedirect());
        verifyNoInteractions(renderer);
    }

    @Test void rejectsDuplicateDeliveryMarkersFromTrustedProxy() throws Exception {
        mvc.perform(peer("/img?formula=x", "172.30.86.2").header("X-Forwarded-For", "203.0.113.54")
                .header("X-Permmath-Image-Delivery", "1", "1")).andExpect(status().isTemporaryRedirect());
        verifyNoInteractions(renderer);
    }

    @Test void trustedImageIngressPreservesSvgHeadersAndPublicUrlPointsToImageOrigin() throws Exception {
        when(renderer.render("delivery-safe")).thenReturn("<svg xmlns='http://www.w3.org/2000/svg'><text>safe</text></svg>");
        mvc.perform(peer("/img", "172.30.86.2").param("formula", "delivery-safe")
                .header("X-Forwarded-For", "203.0.113.55").header("X-Permmath-Image-Delivery", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.permmath.rendering.SvgOutputPolicy.CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(peer("/url", "203.0.113.56").param("formula", "<p>public user</p>"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "https://math-images.example.net/image?ubcq=")));
        mvc.perform(peer("/v3/api-docs", "203.0.113.57")).andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("https://math.example.com"));
    }

    @Test void requiredBoundaryRejectsSingleOriginOrMissingTrustedProxyConfiguration() {
        var local = new org.permmath.PermMathProperties("http://localhost:8086", 800, 2000.0f);
        var noProxy = new org.permmath.ratelimit.ClientRateLimitProperties(600, 120, 32, 60, 10, 1,
                100000, java.time.Duration.ofHours(1), java.util.List.of());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ImageDeliveryBoundary(new ImageDeliveryProperties(true), local, noProxy));
        var separate = new org.permmath.PermMathProperties("https://math.example.com", "https://math-images.example.net", 800, 2000.0f);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ImageDeliveryBoundary(new ImageDeliveryProperties(true), separate, noProxy));
    }
}
