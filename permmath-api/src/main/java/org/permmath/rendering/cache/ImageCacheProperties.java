package org.permmath.rendering.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("permmath.image-cache")
public record ImageCacheProperties(
        @DefaultValue("1GB") DataSize maximumSize,
        @DefaultValue("60d") Duration expireAfterAccess) {
    public ImageCacheProperties {
        if (maximumSize.toBytes() <= 0 || expireAfterAccess.isNegative() || expireAfterAccess.isZero()) {
            throw new IllegalArgumentException("Image cache size and expiry must be positive");
        }
    }
}
