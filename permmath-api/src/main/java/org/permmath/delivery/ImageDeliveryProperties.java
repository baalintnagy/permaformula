package org.permmath.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("permmath.image-delivery")
public record ImageDeliveryProperties(@DefaultValue("false") boolean required) { }
