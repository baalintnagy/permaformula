package org.permaformula;

import org.boava.exception.LocalExceptionHandler;
import org.permaformula.delivery.ImageDeliveryProperties;
import org.permaformula.ratelimit.ClientRateLimitProperties;
import org.permaformula.rendering.ApiWorkProperties;
import org.permaformula.rendering.RenderingProperties;
import org.permaformula.rendering.StylesheetFetchProperties;
import org.permaformula.rendering.cache.ImageCacheProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@EnableConfigurationProperties({
        PermaFormulaProperties.class,
        RenderingProperties.class,
        ImageCacheProperties.class,
        ApiWorkProperties.class,
        StylesheetFetchProperties.class,
        ClientRateLimitProperties.class,
        ImageDeliveryProperties.class })
@SpringBootApplication
@Import(LocalExceptionHandler.class)
public class PermaFormulaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PermaFormulaApplication.class, args);
    }

}
