package org.permmath.rendering.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("permmath.image-cache")
public record ImageCacheProperties(
        @DefaultValue("256MB") DataSize maximumSize,
        @DefaultValue("60d") Duration expireAfterAccess) {
    public ImageCacheProperties {
        if (maximumSize.toBytes() <= 0 || expireAfterAccess.isNegative() || expireAfterAccess.isZero()) {
            throw new IllegalArgumentException("Image cache size and expiry must be positive");
        }
    }

    /** Limit estimated cache weight to one quarter of the heap to leave processing headroom. */
    public long effectiveMaximumBytes(long maximumHeapBytes) {
        if (maximumHeapBytes <= 0) {
            throw new IllegalArgumentException("Maximum heap must be positive");
        }
        return Math.min(maximumSize.toBytes(), Math.max(1, maximumHeapBytes / 4));
    }
}
