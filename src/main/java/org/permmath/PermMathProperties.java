package org.permmath;

import javax.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;


@Validated
@Component
@ConfigurationProperties(prefix = "permmath", ignoreUnknownFields = false)
@Data
//@AllArgsConstructor(onConstructor_ = { @ConstructorBinding })
public class PermMathProperties {

    @NotBlank
    private /* final */ String host;
}
