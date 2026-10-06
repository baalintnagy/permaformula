package org.permmath.rendering;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("permmath.api-work")
public record ApiWorkProperties(
        @DefaultValue("4") int preparationConcurrency,
        @DefaultValue("2") int generationConcurrency,
        @DefaultValue("2") int fetchConcurrency,
        @DefaultValue("30s") Duration timeout) {
    public ApiWorkProperties {
        if (preparationConcurrency < 1 || generationConcurrency < 1 || fetchConcurrency < 1
                || timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("API work limits must be positive; timeout must be at most five minutes");
        }
    }

    public static ApiWorkProperties defaults() {
        return new ApiWorkProperties(4, 2, 2, Duration.ofSeconds(30));
    }
}
