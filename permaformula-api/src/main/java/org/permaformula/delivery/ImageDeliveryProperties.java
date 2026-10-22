package org.permaformula.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("permaformula.image-delivery")
public record ImageDeliveryProperties(
    @DefaultValue("false") boolean required,
    @DefaultValue("false") boolean separateOriginRequired) {

    @ConstructorBinding
    public ImageDeliveryProperties {
    }

    public ImageDeliveryProperties(boolean required) {
        this(required, false);
    }
}
