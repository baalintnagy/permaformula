package org.permmath.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("permmath.image-delivery")
public record ImageDeliveryProperties(@DefaultValue("false") boolean required,
        @DefaultValue("false") boolean separateOriginRequired) {
    @ConstructorBinding
    public ImageDeliveryProperties { }
    public ImageDeliveryProperties(boolean required) {
        this(required, false);
    }
}
