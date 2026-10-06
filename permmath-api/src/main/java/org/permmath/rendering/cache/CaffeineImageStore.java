package org.permmath.rendering.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CaffeineImageStore implements GeneratedImageStore {

    private static final Logger log = LoggerFactory.getLogger(CaffeineImageStore.class);

    private final Cache<String, String> cache;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<String>> loading
            = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public CaffeineImageStore(ImageCacheProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    CaffeineImageStore(ImageCacheProperties properties, Ticker ticker) {
        long effectiveBytes = properties.effectiveMaximumBytes(Runtime.getRuntime().maxMemory());
        cache = // Conservative UTF-16 content estimate plus key and per-entry overhead.
        Caffeine.newBuilder().maximumWeight(effectiveBytes).weigher((String key, String svg) -> (int) Math.min(Integer.MAX_VALUE, 2L * (key.length() + svg.length()) + 256)).expireAfterAccess(properties.expireAfterAccess()).removalListener((String key, String svg, com.github.benmanes.caffeine.cache.RemovalCause cause) -> log.debug("event=cache_removed key={} cause={}", key, cause)).ticker(ticker).recordStats().build();
        log.info("event=cache_initialized configuredBytes={} effectiveBytes={} expireAfterAccess={}",
                properties.maximumSize().toBytes(), effectiveBytes, properties.expireAfterAccess());
    }

    @Override
    public String getOrGenerate(String key, Generator generator) throws IOException {
        String cached = cache.getIfPresent(key);
        if (cached != null) {
            log.info("event=cache_hit key={} source=cached", key);
            return cached;
        }
        var completion = new java.util.concurrent.CompletableFuture<String>();
        var pending = loading.putIfAbsent(key, completion);
        if (pending != null) {
            try {
                return pending.get(org.permmath.rendering.WorkDeadline.remainingNanos(java.time.Duration.ofSeconds(30)),
                        TimeUnit.NANOSECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "Waiting for image exceeded request deadline");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.GATEWAY_TIMEOUT, "Image wait interrupted");
            } catch (java.util.concurrent.ExecutionException failed) {
                if (failed.getCause() instanceof IOException checked) throw checked;
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failed.getCause() instanceof Error error) throw error;
                throw new IOException("Image generation failed", failed.getCause());
            }
        }
        try {
            String result = load(key, generator);
            completion.complete(result);
            return result;
        } catch (IOException | RuntimeException | Error failed) {
            completion.completeExceptionally(failed);
            throw failed;
        } finally {
            loading.remove(key, completion);
        }
    }

    private String load(String key, Generator generator) throws IOException {
        // cache.get keeps lookup and generation atomic. Other callers may reuse an in-flight load.
        boolean[] generated = { false };
        try {
            var svg = cache.get(key, _ -> {
                generated[0] = true;
                long started = System.nanoTime();
                log.info("event=cache_miss key={}", key);
                try {
                    var output = generator.generate();
                    log.info("event=cache_generated key={} svgChars={} durationMs={}",
                            key, output.length(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    return output;
                } catch (IOException | RuntimeException failure) {
                    log.warn("event=cache_generation_failed key={} errorType={} durationMs={}",
                            key, failure.getClass().getSimpleName(),
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    if (failure instanceof IOException checked) {
                        throw new UncheckedIOException(checked);
                    }
                    throw (RuntimeException) failure;
                }
            });
            if (!generated[0]) {
                log.info("event=cache_hit key={} source=cached_or_shared", key);
            }
            return svg;
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    public CacheStats stats() {
        return cache.stats();
    }

    void cleanUp() {
        cache.cleanUp();
    }
}
