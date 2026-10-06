package org.permmath.rendering.cache;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.permmath.rendering.WorkDeadline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

@Component
public class CaffeineImageStore implements GeneratedImageStore {

    private static final Logger log = LoggerFactory.getLogger(CaffeineImageStore.class);

    private final AsyncCache<String, String> cache;

    @Autowired
    public CaffeineImageStore(ImageCacheProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    CaffeineImageStore(ImageCacheProperties properties, Ticker ticker) {
        long effectiveBytes = properties.effectiveMaximumBytes(Runtime.getRuntime().maxMemory());
        // Conservative UTF-16 content estimate plus key and per-entry overhead.
        cache = Caffeine.newBuilder()
            .maximumWeight(effectiveBytes)
            .weigher((String key, String svg) -> (int) Math.min(Integer.MAX_VALUE,
                2L * (key.length() + svg.length()) + 256))
            .expireAfterAccess(properties.expireAfterAccess())
            .removalListener((String key, String svg, RemovalCause cause) ->
                log.debug("event=cache_removed key={} cause={}", key, cause))
            .ticker(ticker)
            .recordStats()
            .buildAsync();
        log.info("event=cache_initialized configuredBytes={} effectiveBytes={} expireAfterAccess={}",
                properties.maximumSize().toBytes(), effectiveBytes, properties.expireAfterAccess());
    }

    @Override
    public String getOrGenerate(String key, Generator generator) throws IOException {
        boolean[] owner = { false };
        // Publish through Caffeine before running the loader, outside its atomic mapping function.
        var completion = cache.get(key, (_, _) -> {
            owner[0] = true;
            return new CompletableFuture<String>();
        });
        if (owner[0]) {
            // Keep the initiating request's deadline and client context on this thread.
            // The generator itself owns bounded execution and cancellation of its actual worker.
            long started = System.nanoTime();
            log.info("event=cache_miss key={}", key);
            try {
                String output = generator.generate();
                log.info("event=cache_generated key={} svgChars={} durationMs={}",
                        key, output.length(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                completion.complete(output);
                return output;
            } catch (IOException | RuntimeException | Error failure) {
                log.warn("event=cache_generation_failed key={} errorType={} durationMs={}",
                        key, failure.getClass().getSimpleName(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                // Caffeine logs failed async loads. Keep submitted content out of those diagnostics.
                completion.completeExceptionally(new GenerationFailure(failure));
                throw failure;
            }
        }
        log.info("event=cache_hit key={} source=cached_or_shared", key);
        try {
            // A waiter's timeout must never complete or cancel the shared future.
            return completion.get(completion.isDone() ? 0 : WorkDeadline.remainingNanos(Duration.ofSeconds(60)),
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "Waiting for image exceeded request deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Image wait interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() instanceof GenerationFailure generation ? generation.original
                    : failed.getCause();
            if (cause instanceof IOException checked) {
                throw checked;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Image generation failed", cause);
        }
    }

    /** Preserve the original for waiters without exposing its message/cause to library logging. */
    private static final class GenerationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final Throwable original;

        GenerationFailure(Throwable original) {
            super("Image generation failed", null, false, false);
            this.original = original;
        }
    }

    public CacheStats stats() {
        return cache.synchronous().stats();
    }

    void cleanUp() {
        cache.synchronous().cleanUp();
    }
}
