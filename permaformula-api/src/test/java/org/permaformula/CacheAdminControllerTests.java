package org.permaformula;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.boava.exception.LocalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.permaformula.rendering.cache.CaffeineImageStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

@SpringJUnitConfig(CacheAdminControllerTests.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "permaformula.admin.password=test-password")
class CacheAdminControllerTests {
    @Configuration
    @EnableWebMvc
    @Import({ AdminSecurityConfiguration.class, AdminLoginController.class,
            CacheAdminController.class, LocalExceptionHandler.class })
    static class Config {
        @Bean
        org.permaformula.ratelimit.ClientRateLimits limits() {
            return new org.permaformula.ratelimit.ClientRateLimits(
                new org.permaformula.ratelimit.ClientRateLimitProperties(
                    600, 120, 32, 60, 10, 1, 1000, java.time.Duration.ofHours(1),
                    java.util.List.of()));
        }

        @Bean
        CaffeineImageStore store() {
            return mock(CaffeineImageStore.class);
        }
    }

    @Autowired
    WebApplicationContext context;
    @Autowired
    CaffeineImageStore store;
    @Autowired
    JwtEncoder encoder;
    MockMvc mvc;
    String id = "a".repeat(64);

    @BeforeEach
    void setup() {
        reset(store);
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void loginIssuesJwtAndDeletionRequiresIt() throws Exception {
        mvc.perform(delete("/admin/cache-item")
            .param("id", id))
            .andExpect(status()
                .isUnauthorized());
        mvc.perform(post("/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status()
                .isUnauthorized());
        verifyNoInteractions(store);
        var login = mvc.perform(post("/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
            .andExpect(status()
                .isOk())
            .andExpect(header()
                .string("Cache-Control", "no-store"))
            .andReturn()
            .getResponse()
            .getContentAsString();
        String token = new ObjectMapper()
            .readTree(login)
            .get("accessToken")
            .asString();
        mvc.perform(delete("/admin/cache-item")
            .param("id", "bad")
            .header("Authorization",
                "Bearer %s".formatted(token)))
            .andExpect(status()
                .isBadRequest())
            .andExpect(jsonPath("$.violations[0].property")
                .value("id"))
            .andExpect(jsonPath("$.violations[0].code")
                .value("CacheId"))
            .andExpect(jsonPath("$.violations[0].invalidValue")
                .doesNotExist());
        mvc.perform(
            delete("/admin/cache-item")
                .param("id", id)
                .header("Authorization",
                    "Bearer %s".formatted(
                        token)))
            .andExpect(status()
                .isNoContent());
        verify(store)
            .delete(id);
    }

    String jwt(String scope, Instant expiration) {
        return encoder.encode(JwtEncoderParameters.from(JwtClaimsSet.builder()
            .issuer(AdminSecurityConfiguration.ISSUER)
            .subject("admin")
            .issuedAt(Instant.now()
                .minusSeconds(3600))
            .expiresAt(expiration)
            .claim("scope", scope)
            .build()))
            .getTokenValue();
    }

    @Test
    void rejectsExpiredTamperedAndNonAdminTokens() throws Exception {
        mvc.perform(delete("/admin/cache-item;ignored")
            .param("id", id))
            .andExpect(status()
                .isBadRequest());
        mvc.perform(delete("/admin/cache-item")
            .param("id", id)
            .header("Authorization",
                "Bearer %s".formatted(jwt("admin", Instant.now()
                    .minusSeconds(120)))))
            .andExpect(status()
                .isUnauthorized());
        mvc.perform(delete("/admin/cache-item")
            .param("id", id)
            .header("Authorization",
                "Bearer invalid.jwt.signature"))
            .andExpect(status()
                .isUnauthorized());
        mvc.perform(delete("/admin/cache-item")
            .param("id", id)
            .header("Authorization",
                "Bearer %s".formatted(jwt("reader", Instant.now()
                    .plusSeconds(900)))))
            .andExpect(status()
                .isForbidden());
        verifyNoInteractions(store);
    }

    @Test
    void missingPasswordDisablesAdminLogin() {
        var manager = new AdminSecurityConfiguration()
            .adminAuthenticationManager("");
        assertThrows(AuthenticationException.class, () -> manager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated("admin", "anything")));
    }
}
