package org.permaformula;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PermaFormulaPropertiesTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class)
        .withPropertyValues("permaformula.host=https://math.example.com");

    @Test
    void omittedRenderingSettingsUseOriginalDefaults() {
        runner.run(context -> {
            assertThat(context)
                .hasNotFailed();
            assertThat(context.getBean(PermaFormulaProperties.class))
                .isEqualTo(
                    new PermaFormulaProperties("https://math.example.com", 500, 2000.0f));
        });
    }

    @Test
    void renderingSettingsCanBeOverridden() {
        runner.withPropertyValues("permaformula.default-image-width=1200",
            "permaformula.default-dpi=300")
            .run(context -> {
                assertThat(context)
                    .hasNotFailed();
                assertThat(context.getBean(PermaFormulaProperties.class))
                    .isEqualTo(
                        new PermaFormulaProperties("https://math.example.com", 1200, 300.0f));
            });
    }

    @Test
    void imageOriginIsIndependentAndTrailingSlashIsNormalized() {
        runner.withPropertyValues("permaformula.image-host=https://math-images.example.net/")
            .run(context -> {
                assertThat(context)
                    .hasNotFailed();
                var properties = context.getBean(PermaFormulaProperties.class);
                assertThat(properties.host())
                    .isEqualTo("https://math.example.com");
                assertThat(properties.imageHost())
                    .isEqualTo("https://math-images.example.net");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = { "file:///images", "https://user:pass@images.example.net",
            "https://images.example.net/path",
            "https://images.example.net?x=1", "https://images.example.net#part",
            "http://images.example.net:0",
            "https://images.example.net:65536" })
    void rejectsInvalidImageOriginAtStartup(String origin) {
        runner.withPropertyValues("permaformula.image-host=%s".formatted(origin))
            .run(context -> assertThat(context)
                .hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://math.example.com", "https://MATH.example.com:443/" })
    void acceptsEquivalentImageOriginsForSingleHostDeployment(String origin) {
        runner.withPropertyValues("permaformula.image-host=%s".formatted(origin))
            .run(context -> {
                assertThat(context)
                    .hasNotFailed();
                assertThat(context.getBean(PermaFormulaProperties.class)
                    .sameImageOrigin())
                    .isTrue();
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PermaFormulaProperties.class)
    static class PropertiesConfiguration {
    }
}
