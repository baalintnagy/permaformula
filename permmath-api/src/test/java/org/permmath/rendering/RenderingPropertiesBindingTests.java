package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RenderingPropertiesBindingTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Settings.class);
    @Test
    void bindsIsolatedGrpcEndpointAndDefaultsToTlsForTcp() {
        runner.run(context -> {
            var settings = context.getBean(RenderingProperties.class);
            assertFalse(settings.plaintext());
            assertEquals(Duration.ofSeconds(45), settings.timeout());
            assertNull(settings.socket());
        });
        runner.withPropertyValues("permmath.rendering.socket=/run/permmath/render.sock")
                .run(context -> assertEquals("/run/permmath/render.sock",
                        context.getBean(RenderingProperties.class).socket().toString().replace('\\', '/')));
    }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RenderingProperties.class)
    static class Settings {}
}
