package org.permaformula.delivery;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.permaformula.rendering.FormulaSvgRenderer;
import org.permaformula.rendering.SvgOutputPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = { "permaformula.host=https://math.example.com",
        "permaformula.image-host=https://math.example.com",
        "permaformula.image-delivery.required=true",
        "permaformula.rate-limit.trusted-proxies=172.30.86.2" })
@AutoConfigureMockMvc
class SingleHostImageDeliveryTests {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    FormulaSvgRenderer renderer;

    @Test
    void jpegRoutesRequireTrustedIngressBeforeRenderingOrDecoding() throws Exception {
        mvc.perform(get("/exp-jpg").param("formula", "x"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/html-jpg").param("ubcq", "malformed"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(renderer);
    }

    @Test
    void rejectsUntrustedMissingAndDuplicateMarkersWithoutRedirectLoops() throws Exception {
        mvc.perform(
            get("/exp-svg").param("formula", "x").header("X-PermaFormula-Image-Delivery", "1"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Location"));
        for (String[] markers : new String[][] { {}, { "1", "1" } }) {
            var request = get("/exp-svg").param("formula", "x").with(r -> {
                r.setRemoteAddr("172.30.86.2");
                r.addHeader("X-Forwarded-For", "203.0.113.60");
                return r;
            });
            if (markers.length > 0) {
                request.header("X-PermaFormula-Image-Delivery", (Object[]) markers);
            }
            mvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(renderer);
    }

    @Test
    void protectedSameOriginImagesKeepSandboxAndPermalinkHost() throws Exception {
        when(renderer.render("single-host-safe"))
            .thenReturn("<svg xmlns='http://www.w3.org/2000/svg'/>");
        mvc.perform(get("/exp-svg").param("formula", "single-host-safe")
            .header("X-PermaFormula-Image-Delivery", "1").with(r -> {
                r.setRemoteAddr("172.30.86.2");
                r.addHeader("X-Forwarded-For", "203.0.113.60");
                return r;
            }))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Security-Policy", SvgOutputPolicy.CSP))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get("/ubcq").param("formula", "x"))
            .andExpect(status().isOk()).andExpect(content().string(
                org.hamcrest.Matchers.startsWith("H4sI")));
    }
}
