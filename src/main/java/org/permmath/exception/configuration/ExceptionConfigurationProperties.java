package org.permmath.exception.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import lombok.Data;

@Validated
@Component
@ConfigurationProperties(prefix = "permmath.exception")
@Data
//@AllArgsConstructor(onConstructor_ = { @ConstructorBinding })
public class ExceptionConfigurationProperties {

    private boolean allowMessage;

    private boolean allowLocalizedMessage;

    private boolean allowExceptionClass;

    private boolean allowStacktrace;

}
