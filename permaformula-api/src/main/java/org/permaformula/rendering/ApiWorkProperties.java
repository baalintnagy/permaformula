package org.permaformula.rendering;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.permaformula.validation.QuickValidator;
import org.permaformula.validation.RecordValidation;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("permaformula.api-work")
public record ApiWorkProperties(
    @DefaultValue("4") @Positive int preparationConcurrency,
    @DefaultValue("2") @Positive int generationConcurrency,
    @DefaultValue("2") @Positive int fetchConcurrency,
    @DefaultValue("60s") @NotNull Duration timeout) {
    public ApiWorkProperties {
        RecordValidation.requireValid(ApiWorkProperties.class, preparationConcurrency,
            generationConcurrency, fetchConcurrency, timeout);
        QuickValidator.validate(
            !(timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0),
            () -> new IllegalArgumentException(
                "API work limits must be positive; timeout must be at most five minutes"));
    }

    public static ApiWorkProperties defaults() {
        return new ApiWorkProperties(4, 2, 2, Duration.ofSeconds(60));
    }
}
