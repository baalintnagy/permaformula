package org.permaformula.rendering.cache;

import static org.permaformula.validation.QuickValidator.validate;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("permaformula.image-cache")
public record ImageCacheProperties(
    @DefaultValue("256MB") DataSize maximumSize,
    @DefaultValue("60d") Duration expireAfterAccess) {
    public ImageCacheProperties {
        validate(
            !(maximumSize.toBytes() <= 0
                || expireAfterAccess.isNegative()
                || expireAfterAccess.isZero()),
            () -> new IllegalArgumentException("Image cache size and expiry must be positive"));
    }

    /** Limit estimated cache weight to one quarter of the heap to leave processing headroom. */
    public long effectiveMaximumBytes(long maximumHeapBytes) {
        validate(
            !(maximumHeapBytes <= 0),
            () -> new IllegalArgumentException("Maximum heap must be positive"));
        return Math.min(maximumSize.toBytes(),
            Math.max(1, maximumHeapBytes / 4));
    }
}
