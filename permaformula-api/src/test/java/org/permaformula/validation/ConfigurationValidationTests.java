package org.permaformula.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.permaformula.ratelimit.ClientRateLimitProperties;
import org.permaformula.rendering.ApiWorkProperties;
import org.permaformula.rendering.cache.PersistentCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

class ConfigurationValidationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ ApiWorkProperties.class, ClientRateLimitProperties.class,
            PersistentCacheProperties.class })
    static class PropertiesConfiguration {
    }

    @Test
    void scalarConstraintsApplyToDirectConstructionBeforeAnySpringProxy() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ApiWorkProperties(0, 1, 1, Duration.ofSeconds(1)))
            .withMessageContaining("preparationConcurrency");
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ApiWorkProperties(1, 0, 1, Duration.ofSeconds(1)))
            .withMessageContaining("generationConcurrency");
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ApiWorkProperties(1, 1, 0, Duration.ofSeconds(1)))
            .withMessageContaining("fetchConcurrency");
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ApiWorkProperties(1, 1, 1, null))
            .withMessageContaining("timeout");
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ClientRateLimitProperties(0, 1, 1, 1, 1, 1, 1,
                Duration.ofMinutes(1), List.of()))
            .withMessageContaining("requestsPerMinute");
    }

    @Test
    void persistenceRangesRetainTheirExistingInclusiveBoundaries() {
        assertDoesNotThrow(() -> persistence(1, 1));
        assertDoesNotThrow(() -> persistence(32, 1_000_000));
        assertThatIllegalArgumentException().isThrownBy(() -> persistence(0, 1))
            .withMessageContaining("maximumPoolSize");
        assertThatIllegalArgumentException().isThrownBy(() -> persistence(33, 1))
            .withMessageContaining("maximumPoolSize");
        assertThatIllegalArgumentException().isThrownBy(() -> persistence(1, 0))
            .withMessageContaining("maximumEntries");
        assertThatIllegalArgumentException().isThrownBy(() -> persistence(1, 1_000_001))
            .withMessageContaining("maximumEntries");
    }

    @Test
    void constructorErrorsDoNotExposeOtherArgumentsOrCredentials() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> new PersistentCacheProperties(PersistentCacheProperties.Backend.POSTGRES,
                Path.of("unused.sqlite"), "jdbc:postgresql://localhost/private-database",
                "private-user", "private-password", 0, DataSize.ofMegabytes(1),
                Duration.ofSeconds(1), 1))
            .withMessageContaining("maximumPoolSize")
            .withMessageNotContaining("private-user")
            .withMessageNotContaining("private-password")
            .withMessageNotContaining("private-database");
    }

    @Test
    void domainChecksStillRejectInvalidTimeoutsAndRefillRelationships() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ApiWorkProperties(1, 1, 1, Duration.ofMinutes(6)));
        assertThatIllegalArgumentException().isThrownBy(
            () -> new ClientRateLimitProperties(1, 10, 1, 1, 10, 1, 1,
                Duration.ofSeconds(1), List.of()))
            .withMessageContaining("full refill");
    }

    @ParameterizedTest
    @ValueSource(strings = { "permaformula.api-work.preparation-concurrency=0",
            "permaformula.api-work.generation-concurrency=-1",
            "permaformula.api-work.fetch-concurrency=0",
            "permaformula.rate-limit.request-burst=0",
            "permaformula.image-cache.persistence.maximum-pool-size=33",
            "permaformula.image-cache.persistence.maximum-entries=1000001" })
    void invalidConfigurationStillPreventsStartup(String property) {
        context.withPropertyValues(property)
            .run(result -> assertThat(result.getStartupFailure()).isNotNull());
    }

    @Test
    void defaultsBindSuccessfullyWithDeclarativeAndConstructorValidation() {
        context.run(result -> {
            assertThat(result.getStartupFailure()).isNull();
            assertThat(result.getBean(ApiWorkProperties.class).preparationConcurrency())
                .isEqualTo(4);
            assertThat(result.getBean(PersistentCacheProperties.class).maximumPoolSize())
                .isEqualTo(4);
        });
    }

    private static PersistentCacheProperties persistence(int pool, long entries) {
        return new PersistentCacheProperties(PersistentCacheProperties.Backend.MEMORY,
            Path.of("unused.sqlite"), null, null, null, pool, DataSize.ofMegabytes(1),
            Duration.ofSeconds(1), entries);
    }
}
