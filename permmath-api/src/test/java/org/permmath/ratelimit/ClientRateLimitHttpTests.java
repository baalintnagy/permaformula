package org.permmath.ratelimit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permmath.compression.StringCompressionService;
import org.permmath.rendering.FormulaSvgRenderer;
import org.permmath.rendering.ImageRendererService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.InputStreamResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "permmath.rate-limit.requests-per-minute=3", "permmath.rate-limit.request-burst=3",
        "permmath.rate-limit.generations-per-minute=1", "permmath.rate-limit.generation-burst=1",
        "permmath.rate-limit.trusted-proxies=172.30.86.2"})
@AutoConfigureMockMvc
class ClientRateLimitHttpTests {
    @Autowired MockMvc mvc;
    @MockitoBean FormulaSvgRenderer renderer;
    @MockitoBean ImageRendererService htmlRenderer;

    @BeforeEach void output() throws Exception {
        when(renderer.render(anyString())).thenReturn("<svg xmlns='http://www.w3.org/2000/svg'><text>safe</text></svg>");
    }

    private static MockHttpServletRequestBuilder client(String path, String ip) {
        return get(path).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    @Test
    void encodingSpendsRequestAllowanceButLeavesRenderAllowanceAvailable() throws Exception {
        mvc.perform(client("/url-encode", "203.0.113.90").param("text", "x + y"))
                .andExpect(status().isOk()).andExpect(content().string("x+%2B+y"));
        verifyNoInteractions(renderer, htmlRenderer);
        mvc.perform(client("/img", "203.0.113.90").param("formula", "encoding-quota-render"))
                .andExpect(status().isOk());
        verify(renderer).render("encoding-quota-render");
    }

    @Test
    void requestLimitRunsBeforeInputValidationAndCannotBeBypassedBySpoofedHeaders() throws Exception {
        assertAll(IntStream.range(0, 3).mapToObj(i -> (Executable) () -> {
            mvc.perform(client("/url-encode", "203.0.113.10")
                    .header("X-Forwarded-For", "198.51.100." + i)).andExpect(status().isBadRequest());
        }));
        mvc.perform(client("/url-encode", "203.0.113.10"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "20"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(renderer);
        mvc.perform(client("/url", "203.0.113.11").param("formula", "x")).andExpect(status().isOk());
    }

    @Test
    void renderLimitReturnsRetryHeaderAndCachedImagesRemainAvailable() throws Exception {
        mvc.perform(client("/img", "203.0.113.20").param("formula", "quota-cached"))
                .andExpect(status().isOk());
        mvc.perform(client("/img", "203.0.113.20").param("formula", "quota-uncached"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(client("/img", "203.0.113.20").param("formula", "quota-cached"))
                .andExpect(status().isOk()).andExpect(header().exists("Content-Security-Policy"));
        verify(renderer, times(1)).render("quota-cached");
        verify(renderer, never()).render("quota-uncached");
        mvc.perform(client("/img", "203.0.113.21").param("formula", "quota-other-client"))
                .andExpect(status().isOk());
    }

    @Test
    void trustsOnlyConfiguredPeerAndRequiresSingleLiteralClientAddress() throws Exception {
        mvc.perform(client("/url", "172.30.86.2").param("formula", "x"))
                .andExpect(status().isBadRequest());
        mvc.perform(client("/url", "172.30.86.2").param("formula", "x")
                .header("X-Forwarded-For", "203.0.113.30, 203.0.113.31"))
                .andExpect(status().isBadRequest());
        assertAll(IntStream.range(0, 3).mapToObj(_ -> (Executable) () -> {
            mvc.perform(client("/url", "172.30.86.2").param("formula", "x")
                    .header("X-Forwarded-For", "203.0.113.30")).andExpect(status().isOk());
        }));
        mvc.perform(client("/url", "172.30.86.2").param("formula", "x")
                .header("X-Forwarded-For", "203.0.113.30")).andExpect(status().isTooManyRequests());
        mvc.perform(client("/url", "172.30.86.2").param("formula", "x")
                .header("X-Forwarded-For", "203.0.113.31")).andExpect(status().isOk());
    }

    @Test
    void htmlAndFormulaShareRenderBudgetButHtmlCacheHitsRemainAvailable() throws Exception {
        when(htmlRenderer.getImage(any())).thenAnswer(_ -> new InputStreamResource(
                new ByteArrayInputStream("<svg xmlns='http://www.w3.org/2000/svg'><text>html</text></svg>"
                        .getBytes(StandardCharsets.UTF_8))));
        String descriptor = new StringCompressionService()
                .compress("{\"forumla\":\"<p>rate-shared</p>\"}");
        mvc.perform(client("/image", "203.0.113.40").param("ubcq", descriptor)).andExpect(status().isOk());
        mvc.perform(client("/img", "203.0.113.40").param("formula", "html-quota-uncached"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        mvc.perform(client("/image", "203.0.113.40").param("ubcq", descriptor)).andExpect(status().isOk());
        verify(htmlRenderer, times(1)).getImage(any());
        verify(renderer, never()).render("html-quota-uncached");
    }
}
