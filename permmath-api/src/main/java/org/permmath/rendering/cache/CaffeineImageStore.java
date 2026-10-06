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

    @Autowired
    public CaffeineImageStore(ImageCacheProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    CaffeineImageStore(ImageCacheProperties properties, Ticker ticker) {
        cache = // Conservative UTF-16 content estimate plus key and per-entry overhead.
        Caffeine.newBuilder().maximumWeight(properties.maximumSize().toBytes()).weigher((String key, String svg) -> (int) Math.min(Integer.MAX_VALUE, 2L * (key.length() + svg.length()) + 256)).expireAfterAccess(properties.expireAfterAccess()).removalListener((String key, String svg, com.github.benmanes.caffeine.cache.RemovalCause cause) -> log.debug("event=cache_removed key={} cause={}", key, cause)).ticker(ticker).recordStats().build();
        log.info("event=cache_initialized maximumBytes={} expireAfterAccess={}",
                properties.maximumSize().toBytes(), properties.expireAfterAccess());
    }

    @Override
    public String getOrGenerate(String key, Generator generator) throws IOException {
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
