package org.permmath.rendering.cache;

import java.io.IOException;
import java.io.UncheckedIOException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CaffeineImageStore implements GeneratedImageStore {
    private final Cache<String, String> cache;

    @Autowired
    public CaffeineImageStore(ImageCacheProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    CaffeineImageStore(ImageCacheProperties properties, Ticker ticker) {
        cache = Caffeine.newBuilder()
                .maximumWeight(properties.maximumSize().toBytes())
                // Conservative UTF-16 content estimate plus key and per-entry overhead.
                .weigher((String key, String svg) -> (int) Math.min(Integer.MAX_VALUE,
                        2L * (key.length() + svg.length()) + 256))
                .expireAfterWrite(properties.expireAfterWrite())
                .ticker(ticker).recordStats().build();
    }

    @Override
    public String getOrGenerate(String key, Generator generator) throws IOException {
        try {
            return cache.get(key, _ -> {
                try { return generator.generate(); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
            });
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
    }

    public CacheStats stats() { return cache.stats(); }

    void cleanUp() { cache.cleanUp(); }
}
