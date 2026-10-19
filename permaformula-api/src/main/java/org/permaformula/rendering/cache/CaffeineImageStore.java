package org.permaformula.rendering.cache;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.google.common.util.concurrent.Striped;
import jakarta.annotation.PreDestroy;
import org.permaformula.rendering.UbcqIdentity;
import org.permaformula.rendering.WorkDeadline;
import org.permaformula.rendering.cache.JdbcImageCache.Stored;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CaffeineImageStore implements GeneratedImageStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CaffeineImageStore.class);

    private final AsyncCache<String, Entry> cache;
    private final Cache<String, String> tokens;
    private final JdbcImageCache persistent;
    private final Ticker ticker;
    private final ThreadPoolExecutor publication;
    private final Striped<Lock> publicationLocks = Striped.lock(64);

    @Autowired
    public CaffeineImageStore(ImageCacheProperties properties,
        ObjectProvider<JdbcImageCache> persistent) {
        this(properties, Ticker.systemTicker(), persistent.getIfAvailable());
    }

    public CaffeineImageStore(ImageCacheProperties properties) {
        this(properties, Ticker.systemTicker(), null);
    }

    CaffeineImageStore(ImageCacheProperties properties, Ticker ticker) {
        this(properties, ticker, null);
    }

    CaffeineImageStore(ImageCacheProperties properties, Ticker ticker, JdbcImageCache persistent) {
        this.persistent = persistent;
        this.ticker = ticker;
        publication = persistent == null ? null
            : new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "image-cache-publication");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        long effectiveBytes = properties.effectiveMaximumBytes(Runtime.getRuntime().maxMemory());
        long tokenBytes = effectiveBytes < 65536 ? 0
            : Math.min(4L * 1024 * 1024, effectiveBytes / 64);
        tokens = Caffeine.newBuilder().maximumWeight(tokenBytes)
            .weigher((String token, String key) -> 512)
            .expireAfterAccess(properties.expireAfterAccess()).ticker(ticker).build();
        // Conservative UTF-16 content estimate plus key and per-entry overhead.
        cache = Caffeine.newBuilder()
            .maximumWeight(effectiveBytes - tokenBytes)
            .weigher((String key, Entry entry) -> (int) Math.min(Integer.MAX_VALUE,
                2L * (key.length() + entry.output.length()) + 320))
            .expireAfterAccess(properties.expireAfterAccess())
            .removalListener((String key, Entry _, RemovalCause cause) -> log
                .debug("event=cache_removed key={} cause={}", key, cause))
            .ticker(ticker)
            .recordStats()
            .buildAsync();
        log.info(
            "event=cache_initialized configuredBytes={} effectiveBytes={} expireAfterAccess={}",
            properties.maximumSize().toBytes(), effectiveBytes, properties.expireAfterAccess());
    }

    @Override
    public String getOrGenerate(String key, Generator generator) throws IOException {
        boolean[] loaded = { false };
        Entry entry = memoryGetOrGenerate(key, () -> {
            loaded[0] = true;
            Stored stored = persistent == null ? null : persistent.findStored(key);
            if (stored != null) {
                persistent.accessed(key);
                return new Entry(stored.output(), stored.described());
            }
            String generated = generator.generate();
            if (persistent != null) {
                persistent.save(key, generated);
            }
            return new Entry(generated, false);
        });
        if (persistent != null
            && !loaded[0]) {
            persistent.accessed(key);
        }
        return entry.output;
    }

    @Override
    public String findByToken(String tokenKey) {
        String key = tokens.getIfPresent(tokenKey);
        if (key == null
            && persistent != null) {
            key = persistent.keyForToken(tokenKey);
        }
        if (key == null) {
            return null;
        }
        Entry entry = cache.synchronous().getIfPresent(key);
        if (entry == null
            && persistent != null) {
            Stored stored = persistent.findStored(key);
            if (stored != null) {
                entry = new Entry(stored.output(), stored.described());
                cache.asMap().putIfAbsent(key, CompletableFuture.completedFuture(entry));
            }
        }
        if (entry == null) {
            tokens.invalidate(tokenKey);
            return null;
        }
        tokens.put(tokenKey, key);
        if (persistent != null) {
            persistent.accessed(key);
        }
        return entry.output;
    }

    @Override
    public void rememberToken(String tokenKey, String key) {
        if (tokenKey == null) {
            return;
        }
        Entry entry = cache.synchronous().getIfPresent(key);
        // Until publication succeeds keep the normal path's bounded retry behavior.
        if (entry != null
            && (persistent == null
                || entry.described)) {
            tokens.put(tokenKey, key);
        }
    }

    @Override
    public void describe(String key, String kind, Supplier<String> descriptor, String excerpt) {
        describe(key, kind, descriptor, excerpt,
            UbcqIdentity.context(kind, 500));
    }

    @Override
    public void describe(String key, String kind, Supplier<String> descriptor, String excerpt,
        String tokenContext) {
        if (persistent == null) {
            return;
        }
        Entry entry = cache.synchronous().getIfPresent(key);
        if (entry == null
            || entry.described
            || ticker.read() < entry.retryAt
            || !entry.publishing.compareAndSet(false, true)) {
            return;
        }
        try {
            publication.execute(() -> {
                Lock lock = publicationLocks.get(key);
                if (!lock.tryLock()) {
                    entry.retryAt = ticker.read() + TimeUnit.SECONDS.toNanos(1);
                    entry.publishing.set(false);
                    return;
                }
                try {
                    // An evicted/deleted entry must not be republished by queued work.
                    if (cache.synchronous().getIfPresent(key) != entry) {
                        return;
                    }
                    Boolean described = persistent.sourcePublished(key);
                    if (described == null) {
                        persistent.saveIfAbsent(key, entry.output);
                    }
                    entry.described = Boolean.TRUE.equals(described)
                        || persistent.describe(key, kind, descriptor.get(), excerpt, tokenContext);
                } catch (RuntimeException failure) {
                    log.debug("event=cache_publication_failed key={} errorType={}", key,
                        failure.getClass().getSimpleName());
                } finally {
                    if (!entry.described) {
                        entry.retryAt = ticker.read() + TimeUnit.SECONDS.toNanos(30);
                    }
                    entry.publishing.set(false);
                    lock.unlock();
                }
            });
        } catch (RejectedExecutionException _) {
            entry.retryAt = ticker.read() + TimeUnit.SECONDS.toNanos(1);
            entry.publishing.set(false);
        }
    }

    @FunctionalInterface
    private interface Loader {
        Entry generate() throws IOException;
    }

    private Entry memoryGetOrGenerate(String key, Loader generator) throws IOException {
        boolean[] owner = { false };
        // Publish through Caffeine before running the loader, outside its atomic mapping function.
        CompletableFuture<Entry> completion = cache.get(key, (_, _) -> {
            owner[0] = true;
            return new CompletableFuture<Entry>();
        });
        if (owner[0]) {
            // Keep the initiating request's deadline and client context on this thread.
            // The generator itself owns bounded execution and cancellation of its actual worker.
            long started = System.nanoTime();
            log.info("event=cache_miss key={}", key);
            try {
                Entry output = generator.generate();
                log.info("event=cache_generated key={} svgChars={} durationMs={}",
                    key, output.output.length(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                completion.complete(output);
                return output;
            } catch (IOException | RuntimeException | Error failure) {
                log.warn("event=cache_generation_failed key={} errorType={} durationMs={}",
                    key, failure.getClass().getSimpleName(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                // Caffeine logs failed async loads. Keep submitted content out of those
                // diagnostics.
                completion.completeExceptionally(new GenerationFailure(failure));
                throw failure;
            }
        }
        log.info("event=cache_hit key={} source=cached_or_shared", key);
        try {
            // A waiter's timeout must never complete or cancel the shared future.
            return completion.get(
                completion.isDone() ? 0 : WorkDeadline.remainingNanos(Duration.ofSeconds(60)),
                TimeUnit.NANOSECONDS);
        } catch (TimeoutException _) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                "Waiting for image exceeded request deadline");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Image wait interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() instanceof GenerationFailure generation
                ? generation.original
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

    private static final class Entry {
        final String output;
        final AtomicBoolean publishing = new AtomicBoolean();
        volatile boolean described;
        volatile long retryAt = Long.MIN_VALUE;

        Entry(String output, boolean described) {
            this.output = output;
            this.described = described;
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
        return cache.synchronous()
            .stats();
    }

    public void delete(String key) throws SQLException {
        Lock lock = publicationLocks.get(key);
        if (!lock.tryLock()) {
            throw JdbcImageCache.busy();
        }
        try {
            if (persistent != null) {
                persistent.delete(key);
            }
            cache.synchronous().invalidate(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (publication != null) {
            publication.shutdownNow();
            try {
                publication.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    void cleanUp() {
        cache.synchronous()
            .cleanUp();
    }
}
