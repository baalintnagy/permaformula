package org.permmath;

import org.boava.exception.LocalExceptionHandler;
import org.permmath.delivery.ImageDeliveryProperties;
import org.permmath.ratelimit.ClientRateLimitProperties;
import org.permmath.rendering.ApiWorkProperties;
import org.permmath.rendering.RenderingProperties;
import org.permmath.rendering.StylesheetFetchProperties;
import org.permmath.rendering.cache.ImageCacheProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@EnableConfigurationProperties({
    PermMathProperties.class,
    RenderingProperties.class,
    ImageCacheProperties.class,
    ApiWorkProperties.class,
    StylesheetFetchProperties.class,
    ClientRateLimitProperties.class,
    ImageDeliveryProperties.class })
@SpringBootApplication
@Import(LocalExceptionHandler.class)
public class PermMathApplication {

    public static void main(String[] args) {
        SpringApplication.run(PermMathApplication.class, args);
    }

}
