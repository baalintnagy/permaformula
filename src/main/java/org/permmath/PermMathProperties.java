package org.permmath;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;


@Validated
@Component
@ConfigurationProperties(prefix = "permmath")
@Data
//@AllArgsConstructor(onConstructor_ = { @ConstructorBinding })
public class PermMathProperties {

    @NotBlank
    private /* final */ String host;

    private int defaultImageWidth = 800;
    private float defaultDpi = 2000.0f;
}
