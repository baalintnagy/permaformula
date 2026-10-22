package org.permaformula.delivery;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import jakarta.validation.ConstraintViolationException;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permaformula.PermaFormulaProperties;
import org.permaformula.ratelimit.ClientRateLimitProperties;
import org.permaformula.rendering.FormulaSvgRenderer;
import org.permaformula.rendering.SvgOutputPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = { "permaformula.host=https://math.example.com",
        "permaformula.image-host=https://math-images.example.net",
        "permaformula.image-delivery.required=true",
        "permaformula.rate-limit.trusted-proxies=172.30.86.2" })
@AutoConfigureMockMvc
class ImageDeliveryBoundaryTests {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    FormulaSvgRenderer renderer;

    private static MockHttpServletRequestBuilder peer(String path, String ip) {
        return get(URI.create(path))
            .with(request -> {
                request.setRemoteAddr(ip);
                return request;
            });
    }

    @Test
    void redirectsUntrustedOrUnmarkedHandlersBeforeRenderingAndPreservesRawQuery()
        throws Exception {
        assertAll(List.of(
            peer("/exp-svg?formula=x%2By", "203.0.113.50")
                .header("X-PermaFormula-Image-Delivery",
                    "1"),
            peer("/exp-svg?formula=x%2By", "172.30.86.2")
                .header("X-Forwarded-For", "203.0.113.51"))
            .stream()
            .<Executable>map(request -> () -> {
                mvc.perform(request)
                    .andExpect(status()
                        .isTemporaryRedirect())
                    .andExpect(header()
                        .string("Location",
                            "https://math-images.example.net/exp-svg?formula=x%2By"))
                    .andExpect(header()
                        .string("Cache-Control", "no-store"));
            }));
        mvc.perform(peer("/html-svg?ubcq=malformed", "203.0.113.52"))
            .andExpect(status()
                .isTemporaryRedirect());
        mvc.perform(peer("/exp-svg;ignored?formula=x", "203.0.113.53"))
            .andExpect(status()
                .isTemporaryRedirect());
        verifyNoInteractions(renderer);
    }

    @Test
    void rejectsDuplicateDeliveryMarkersFromTrustedProxy() throws Exception {
        mvc.perform(
            peer("/exp-svg?formula=x", "172.30.86.2")
                .header("X-Forwarded-For", "203.0.113.54")
                .header("X-PermaFormula-Image-Delivery", "1", "1"))
            .andExpect(status()
                .isTemporaryRedirect());
        verifyNoInteractions(renderer);
    }

    @Test
    void trustedImageIngressPreservesSvgHeadersAndPublicUrlPointsToImageOrigin() throws Exception {
        when(renderer.render("delivery-safe"))
            .thenReturn("<svg xmlns='http://www.w3.org/2000/svg'><text>safe</text></svg>");
        mvc.perform(peer("/exp-svg", "172.30.86.2")
            .param("formula", "delivery-safe")
            .header("X-Forwarded-For", "203.0.113.55")
            .header("X-PermaFormula-Image-Delivery", "1"))
            .andExpect(status()
                .isOk())
            .andExpect(header()
                .string("Content-Security-Policy", SvgOutputPolicy.CSP))
            .andExpect(header()
                .string("X-Content-Type-Options", "nosniff"))
            .andExpect(header()
                .string("Referrer-Policy", "no-referrer"));
        mvc.perform(peer("/ubcq", "203.0.113.56")
            .param("formula", "<p>public user</p>"))
            .andExpect(status()
                .isOk())
            .andExpect(content()
                .string(Matchers.startsWith(
                    "H4sI")));
        mvc.perform(peer("/v3/api-docs", "203.0.113.57"))
            .andExpect(status()
                .isOk())
            .andExpect(jsonPath("$.servers[0].url")
                .value("https://math.example.com"));
    }

    @Test
    void requiredBoundaryRejectsSingleOriginOrMissingTrustedProxyConfiguration() {
        var local = new PermaFormulaProperties("http://localhost:8086", 800, 2000.0f);
        var noProxy = new ClientRateLimitProperties(600, 120, 32, 60, 10, 1,
            100000, Duration.ofHours(1),
            List.of());
        Assertions.assertThrows(ConstraintViolationException.class,
            () -> new ImageDeliveryBoundary(new ImageDeliveryProperties(true), local, noProxy));
        var separate = new PermaFormulaProperties("https://math.example.com",
            "https://math-images.example.net", 800, 2000.0f);
        var trusted = new ClientRateLimitProperties(600, 120, 32, 60, 10, 1,
            100000, Duration.ofHours(1),
            List.of("172.30.86.2"));
        Assertions.assertThrows(ConstraintViolationException.class,
            () -> new ImageDeliveryBoundary(new ImageDeliveryProperties(true, true), local,
                trusted));
        Assertions.assertThrows(ConstraintViolationException.class,
            () -> new ImageDeliveryBoundary(new ImageDeliveryProperties(false, true), separate,
                trusted));
        Assertions.assertThrows(ConstraintViolationException.class,
            () -> new ImageDeliveryBoundary(new ImageDeliveryProperties(true), separate,
                noProxy));
    }
}
