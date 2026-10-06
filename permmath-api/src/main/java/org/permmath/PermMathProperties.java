package org.permmath;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "permmath")
public record PermMathProperties(
        @NotBlank String host,
        @DefaultValue("800") int defaultImageWidth,
        @DefaultValue("2000.0") float defaultDpi) {
}