package org.permaformula.rendering;

import java.time.Duration;
import java.util.Map;

import org.permaformula.validation.QuickValidator;

/** Deployment bounds, independent of the rendering content limits. */
public record RenderQueueSettings(int capacity, long maxRetainedBytes, Duration maxWait) {
    public RenderQueueSettings {
        QuickValidator.validate(
            !(capacity < 0
                || capacity > 1024
                || maxRetainedBytes < 1
                || maxWait == null
                || maxWait.isNegative()
                || maxWait.isZero()
                || maxWait.compareTo(Duration.ofMinutes(1)) > 0),
            () -> new IllegalArgumentException(
                "Queue capacity must be 0..1024, bytes positive, and wait 0..60 seconds exclusive of zero"));
    }

    public static RenderQueueSettings fromEnvironment(Map<String, String> env) {
        return new RenderQueueSettings(
            Integer.parseInt(env.getOrDefault("RENDERER_QUEUE_CAPACITY", "16")),
            Long.parseLong(env.getOrDefault("RENDERER_QUEUE_MAX_RETAINED_BYTES", "67108864")),
            Duration
                .ofSeconds(Long.parseLong(env.getOrDefault("RENDERER_QUEUE_WAIT_SECONDS", "15"))));
    }

    public long effectiveRetainedBytes() {
        return Math.min(maxRetainedBytes, Runtime.getRuntime().maxMemory() / 4);
    }
}
