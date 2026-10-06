package org.permmath.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class ClientRateLimitPropertiesTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ClientRateLimitProperties.class)
    static class Config { }

    @Test void bindsSafeDefaultsAndExplicitProxyAddresses() {
        context.run(result -> {
            assertNull(result.getStartupFailure());
            var properties = result.getBean(ClientRateLimitProperties.class);
            assertEquals(600, properties.requestsPerMinute());
            assertEquals(32, properties.requestConcurrency());
            assertTrue(properties.trustedProxies().isEmpty());
        });
        context.withPropertyValues("permmath.rate-limit.trusted-proxies=::ffff:172.30.86.2,2001:db8::1")
                .run(result -> assertEquals(java.util.List.of("172.30.86.2", "2001:db8::1"),
                        result.getBean(ClientRateLimitProperties.class).trustedProxies()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requests-per-minute=0", "request-burst=0", "request-concurrency=0",
            "generation-concurrency=0", "maximum-clients=0", "expire-after-access=1s",
            "trusted-proxies=ingress", "trusted-proxies=0.0.0.0/0"})
    void rejectsInvalidLimitsOrProxyTrustAtStartup(String property) {
        context.withPropertyValues("permmath.rate-limit." + property)
                .run(result -> assertNotNull(result.getStartupFailure()));
    }
}
