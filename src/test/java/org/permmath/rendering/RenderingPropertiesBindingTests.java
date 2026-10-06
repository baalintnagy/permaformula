package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RenderingPropertiesBindingTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Settings.class);

    @Test
    void containerCanEnableSandboxWithoutChangingLocalDefaults() {
        runner.run(context -> assertFalse(context.getBean(RenderingProperties.class).chromiumSandbox()));
        runner.withPropertyValues("permmath.rendering.chromium-sandbox=true",
                "permmath.rendering.browser-directory=/opt/permmath/browsers")
                .run(context -> {
                    var settings = context.getBean(RenderingProperties.class);
                    assertTrue(settings.chromiumSandbox());
                    assertEquals("/opt/permmath/browsers", settings.browserDirectory().toString().replace('\\', '/'));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RenderingProperties.class)
    static class Settings {}
}
