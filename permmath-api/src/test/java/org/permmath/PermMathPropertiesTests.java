package org.permmath;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void imageOriginIsIndependentAndTrailingSlashIsNormalized() {
        runner.withPropertyValues("permmath.image-host=https://math-images.example.net/").run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(PermMathProperties.class);
            assertThat(properties.host()).isEqualTo("https://math.example.com");
            assertThat(properties.imageHost()).isEqualTo("https://math-images.example.net");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///images", "https://user:pass@images.example.net", "https://images.example.net/path",
            "https://images.example.net?x=1", "https://images.example.net#part", "http://images.example.net:0",
            "https://images.example.net:65536", "https://math.example.com", "https://MATH.example.com:443/"})
    void rejectsInvalidOrSameImageOriginAtStartup(String origin) {
        runner.withPropertyValues("permmath.image-host=" + origin).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PermMathProperties.class)
    static class PropertiesConfiguration {
    }
}
