package org.permaformula.ratelimit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permaformula.TestServices;
import org.permaformula.compression.StringCompressionService;
import org.permaformula.rendering.FormulaSvgRenderer;
import org.permaformula.rendering.ImageRendererService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest(properties = {
        "permaformula.rate-limit.requests-per-minute=3", "permaformula.rate-limit.request-burst=3",
        "permaformula.rate-limit.generations-per-minute=1",
        "permaformula.rate-limit.generation-burst=1",
        "permaformula.rate-limit.trusted-proxies=172.30.86.2" })
@AutoConfigureMockMvc
class ClientRateLimitHttpTests {
    @Autowired
    MockMvc mvc;
    @MockitoBean
    FormulaSvgRenderer renderer;
    @MockitoBean
    ImageRendererService htmlRenderer;
    @MockitoBean
    AuthenticationManager authentication;

    @Test
    void distributedLoginFloodIsRejectedBeforeAuthenticationInTheRealFilterChain()
        throws Exception {
        when(authentication.authenticate(any()))
            .thenThrow(
                new BadCredentialsException("bad"));
        for (int i = 1; i <= 4; i++) {
            var attempt = mvc.perform(MockMvcRequestBuilders.post("/admin/login")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.%s".formatted(loginClient++));
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"));
            if (i <= 3) {
                attempt.andExpect(status()
                    .isUnauthorized());
            } else {
                attempt.andExpect(status()
                    .isTooManyRequests())
                    .andExpect(header()
                        .exists("Retry-After"))
                    .andExpect(header()
                        .string("Cache-Control", "no-store"));
            }
        }
        verify(authentication, times(3))
            .authenticate(any());
        mvc.perform(client("/exp-svg", "203.0.113.144")
            .param("formula", "login-flood-render"))
            .andExpect(status()
                .isOk());
    }

    private int loginClient = 140;

    @BeforeEach
    void output() throws Exception {
        when(renderer.render(anyString()))
            .thenReturn("<svg xmlns='http://www.w3.org/2000/svg'><text>safe</text></svg>");
    }

    private static MockHttpServletRequestBuilder client(String path, String ip) {
        return get(path)
            .with(request -> {
                request.setRemoteAddr(ip);
                return request;
            });
    }

    @Test
    void ubcqSpendsRequestAllowanceButLeavesRenderAllowanceAvailable() throws Exception {
        mvc.perform(client("/ubcq", "203.0.113.90")
            .param("formula", "x + y"))
            .andExpect(status()
                .isOk())
            .andExpect(content()
                .string(Matchers.startsWith("H4sI")));
        verifyNoInteractions(renderer, htmlRenderer);
        mvc.perform(client("/exp-svg", "203.0.113.90")
            .param("formula", "encoding-quota-render"))
            .andExpect(status()
                .isOk());
        verify(renderer)
            .render("encoding-quota-render");
    }

    @Test
    void requestLimitRunsBeforeInputValidationAndCannotBeBypassedBySpoofedHeaders()
        throws Exception {
        assertAll(IntStream.range(0, 3)
            .mapToObj(i -> (Executable) () -> {
                mvc.perform(client("/ubcq", "203.0.113.10")
                    .header("X-Forwarded-For", "198.51.100.%s".formatted(i)))
                    .andExpect(status()
                        .isBadRequest());
            }));
        mvc.perform(client("/ubcq", "203.0.113.10"))
            .andExpect(status()
                .isTooManyRequests())
            .andExpect(header()
                .string("Retry-After", "20"))
            .andExpect(header()
                .string("Cache-Control", "no-store"));
        verifyNoInteractions(renderer);
        mvc.perform(client("/ubcq", "203.0.113.11")
            .param("formula", "x"))
            .andExpect(status()
                .isOk());
    }

    @Test
    void renderLimitReturnsRetryHeaderAndCachedImagesRemainAvailable() throws Exception {
        mvc.perform(client("/exp-svg", "203.0.113.20")
            .param("formula", "quota-cached"))
            .andExpect(status()
                .isOk());
        mvc.perform(client("/exp-svg", "203.0.113.20")
            .param("formula", "quota-uncached"))
            .andExpect(status()
                .isTooManyRequests())
            .andExpect(header()
                .exists("Retry-After"))
            .andExpect(header()
                .string("Cache-Control", "no-store"));
        mvc.perform(client("/exp-svg", "203.0.113.20")
            .param("formula", "quota-cached"))
            .andExpect(status()
                .isOk())
            .andExpect(header()
                .exists("Content-Security-Policy"));
        verify(renderer, times(1))
            .render("quota-cached");
        verify(renderer, never())
            .render("quota-uncached");
        mvc.perform(client("/exp-svg", "203.0.113.21")
            .param("formula", "quota-other-client"))
            .andExpect(status()
                .isOk());
    }

    @Test
    void trustsOnlyConfiguredPeerAndRequiresSingleLiteralClientAddress() throws Exception {
        mvc.perform(client("/ubcq", "172.30.86.2")
            .param("formula", "x"))
            .andExpect(status()
                .isBadRequest());
        mvc.perform(client("/ubcq", "172.30.86.2")
            .param("formula", "x")
            .header("X-Forwarded-For", "203.0.113.30, 203.0.113.31"))
            .andExpect(status()
                .isBadRequest());
        assertAll(IntStream.range(0, 3)
            .mapToObj(_ -> (Executable) () -> {
                mvc.perform(client("/ubcq", "172.30.86.2")
                    .param("formula", "x")
                    .header("X-Forwarded-For", "203.0.113.30"))
                    .andExpect(status()
                        .isOk());
            }));
        mvc.perform(client("/ubcq", "172.30.86.2")
            .param("formula", "x")
            .header("X-Forwarded-For", "203.0.113.30"))
            .andExpect(status()
                .isTooManyRequests());
        mvc.perform(client("/ubcq", "172.30.86.2")
            .param("formula", "x")
            .header("X-Forwarded-For", "203.0.113.31"))
            .andExpect(status()
                .isOk());
    }

    @Test
    void htmlAndFormulaShareRenderBudgetButHtmlCacheHitsRemainAvailable() throws Exception {
        when(htmlRenderer.getImage(any()))
            .thenAnswer(_ -> new InputStreamResource(
                new ByteArrayInputStream(
                    "<svg xmlns='http://www.w3.org/2000/svg'><text>html</text></svg>"
                        .getBytes(StandardCharsets.UTF_8))));
        String descriptor = TestServices.validated(new StringCompressionService())
            .compress("{\"forumla\":\"<p>rate-shared</p>\"}");
        mvc.perform(client("/html-svg", "203.0.113.40")
            .param("ubcq", descriptor))
            .andExpect(status()
                .isOk());
        mvc.perform(client("/exp-svg", "203.0.113.40")
            .param("formula", "html-quota-uncached"))
            .andExpect(status()
                .isTooManyRequests())
            .andExpect(header()
                .exists("Retry-After"));
        mvc.perform(client("/html-svg", "203.0.113.40")
            .param("ubcq", descriptor))
            .andExpect(status()
                .isOk());
        verify(htmlRenderer, times(1))
            .getImage(any());
        verify(renderer, never())
            .render("html-quota-uncached");
    }
}
