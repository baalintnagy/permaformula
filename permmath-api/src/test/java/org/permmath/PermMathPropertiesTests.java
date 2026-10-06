package org.permmath;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PermMathPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("permmath.host=https://math.example.com");

    @Test
    void omittedRenderingSettingsUseOriginalDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PermMathProperties.class))
                    .isEqualTo(new PermMathProperties("https://math.example.com", 800, 2000.0f));
        });
    }

    @Test
    void renderingSettingsCanBeOverridden() {
        runner.withPropertyValues("permmath.default-image-width=1200", "permmath.default-dpi=300")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PermMathProperties.class))
                            .isEqualTo(new PermMathProperties("https://math.example.com", 1200, 300.0f));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PermMathProperties.class)
    static class PropertiesConfiguration {
    }
}
