package org.permmath.rendering.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class CaffeineImageStoreTests {

    @Test
    void cacheBudgetLeavesHeapHeadroomAndHonorsSmallerConfiguration() {
        var properties = new ImageCacheProperties(DataSize.ofMegabytes(128), Duration.ofHours(1));
        assertEquals(DataSize.ofMegabytes(64).toBytes(),
                properties.effectiveMaximumBytes(DataSize.ofMegabytes(256).toBytes()));
        assertEquals(DataSize.ofMegabytes(128).toBytes(),
                properties.effectiveMaximumBytes(DataSize.ofMegabytes(1024).toBytes()));
        assertThrows(IllegalArgumentException.class, () -> properties.effectiveMaximumBytes(0));
    }

    @Test
    void concurrentRequestsShareOneGeneration() throws Exception {
        var store = store(65536, new AtomicLong());
        var calls = new AtomicInteger();
        var ready = new CountDownLatch(8);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return store.getOrGenerate("same", () -> {
                        calls.incrementAndGet();
                        return "<svg/>";
                    });
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var result : results) {
                assertEquals("<svg/>", result.get(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, calls.get());
        assertEquals(1, store.stats().loadSuccessCount());
    }

    @Test
    void failuresAreNotCachedAndCheckedExceptionsArePreserved() throws Exception {
        var store = store(65536, new AtomicLong());
        var failure = new IOException("render failed");
        assertSame(failure, assertThrows(IOException.class, () -> store.getOrGenerate("key", () -> {
            throw failure;
        })));
        assertThrows(IllegalArgumentException.class, () -> store.getOrGenerate("key", () -> {
            throw new IllegalArgumentException("bad formula");
        }));
        assertEquals("success", store.getOrGenerate("key", () -> "success"));
    }

    @Test
    void expiredContentIsRegeneratedWithoutSleeping() throws Exception {
        var time = new AtomicLong();
        var store = store(65536, time);
        assertEquals("old", store.getOrGenerate("key", () -> "old"));
        time.set(Duration.ofHours(1).toNanos());
        assertEquals("new", store.getOrGenerate("key", () -> "new"));
    }

    @Test
    void cacheHitsResetTheFullConfiguredTtl() throws Exception {
        var time = new AtomicLong();
        var store = store(65536, time);
        assertEquals("original", store.getOrGenerate("key", () -> "original"));
        time.set(Duration.ofMinutes(45).toNanos());
        assertEquals("original", store.getOrGenerate("key", () -> {
            throw new AssertionError("Entry should still be cached");
        }));
        // Beyond the original TTL, but within one TTL of the previous hit.
        time.set(Duration.ofMinutes(90).toNanos());
        assertEquals("original", store.getOrGenerate("key", () -> {
            throw new AssertionError("Hit must extend expiry");
        }));
        time.set(Duration.ofMinutes(151).toNanos());
        assertEquals("regenerated", store.getOrGenerate("key", () -> "regenerated"));
        assertEquals(2, store.stats().loadSuccessCount());
    }

    @Test
    void oversizedImagesAreReturnedButNotRetained() throws Exception {
        var store = store(256, new AtomicLong());
        var calls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            assertEquals("svg".repeat(1000), store.getOrGenerate("key", () -> {
                calls.incrementAndGet();
                return "svg".repeat(1000);
            }));
            // Caffeine eviction maintenance is asynchronous; force it for this assertion.
            store.cleanUp();
        }
        assertEquals(2, calls.get());
    }

    private static CaffeineImageStore store(long bytes, AtomicLong time) {
        return new CaffeineImageStore(new ImageCacheProperties(DataSize.ofBytes(bytes), Duration.ofHours(1)), time::get);
    }
}
