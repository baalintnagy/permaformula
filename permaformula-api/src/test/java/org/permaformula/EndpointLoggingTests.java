package org.permaformula;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.permaformula.rendering.cache.CaffeineImageStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "logging.level.org.permaformula=DEBUG")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class EndpointLoggingTests {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private CaffeineImageStore cache;

    @Test
    void aspectLogsSuccessAndValidationFailureWithoutPayloads(CapturedOutput output)
        throws Exception {
        String formula = "unique-formula-content-for-logging-test";
        mvc.perform(get("/ubcq")
            .param("formula", formula))
            .andExpect(status()
                .isOk());
        mvc.perform(get("/ubcq")
            .param("formula", " "))
            .andExpect(status()
                .isBadRequest());
        assertThat(output.getOut())
            .contains("event=endpoint_started method=GET path=/ubcq",
                "event=endpoint_completed method=GET path=/ubcq", "status=200 durationMs=",
                "event=endpoint_failed method=GET path=/ubcq",
                "errorType=ConstraintViolationException");
        assertThat(output.getOut())
            .doesNotContain(formula);
    }

    @Test
    void loginValidationNeverReflectsOrLogsSubmittedPassword(CapturedOutput output)
        throws Exception {
        String password = "private-login-value-".repeat(8);
        mvc.perform(post("/admin/login")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content(
                """
                {"username":"admin","password":"%s"}
                """
                    .formatted(password)))
            .andExpect(status()
                .isBadRequest())
            .andExpect(jsonPath("$.violations[0].property")
                .value("password"))
            .andExpect(jsonPath("$.violations[0].code")
                .value("Size"))
            .andExpect(jsonPath("$.violations[0].invalidValue")
                .doesNotExist());
        assertThat(output.getAll())
            .doesNotContain(password);
    }

    @Test
    void cacheLogsGenerationReuseAndFailureAndPreservesExceptions(CapturedOutput output)
        throws Exception {
        String key = "logging-test-image";
        cache.getOrGenerate(key, () -> "<svg>private-image-content</svg>");
        cache.getOrGenerate(key, () -> {
            throw new AssertionError("Should reuse cached output");
        });
        var failure = new IOException("private-generation-failure-content",
            new IllegalArgumentException("private-generation-cause-content"));
        assertSame(failure, assertThrows(IOException.class,
            () -> cache.getOrGenerate("logging-test-failure", () -> {
                throw failure;
            })));
        assertThat(output.getOut())
            .contains("event=cache_miss key=%s".formatted(key),
                "event=cache_generated key=%s".formatted(key),
                "event=cache_hit key=%s".formatted(key),
                "event=cache_generation_failed key=logging-test-failure errorType=IOException");
        assertThat(output.getAll())
            .doesNotContain("private-image-content",
                "private-generation-failure-content",
                "private-generation-cause-content");
    }
}
