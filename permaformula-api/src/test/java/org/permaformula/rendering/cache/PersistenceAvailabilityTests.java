package org.permaformula.rendering.cache;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.permaformula.rendering.WorkDeadline;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

class PersistenceAvailabilityTests {
    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg'/>";

    private CaffeineImageStore store(JdbcImageCache backing, AtomicLong ticker) {
        return new CaffeineImageStore(
            new ImageCacheProperties(DataSize.ofMegabytes(1), Duration.ofHours(1)), ticker::get,
            backing);
    }

    @Test
    void publishedWarmHitsPerformNoJdbcOrRepeatedSourceSerialization() throws Exception {
        var backing = mock(JdbcImageCache.class);
        when(backing.describe(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(true);
        var serialized = new AtomicInteger();
        try (var store = store(backing, new AtomicLong())) {
            store.getOrGenerate("warm", () -> SVG);
            store.describe("warm", "formula", () -> {
                serialized.incrementAndGet();
                return "{}";
            }, "x");
            await().atMost(Duration.ofSeconds(3)).until(() -> serialized.get() == 1);
            // Wait for the completion flag, not just entry into the serializer.
            await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> verify(backing).describe("warm", "formula", "{}", "x",
                    org.permaformula.rendering.UbcqIdentity.context("formula", 500)));
            clearInvocations(backing);
            try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
                callers.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        assertEquals(SVG,
                            store.getOrGenerate("warm", () -> fail("Must stay in memory")));
                        store.describe("warm", "formula", () -> fail("Must not serialize again"),
                            "x");
                    }
                    return true;
                }).get(1, TimeUnit.SECONDS);
            }
            verify(backing, times(100)).accessed("warm");
            verifyNoMoreInteractions(backing);
            assertEquals(1, serialized.get());
        }
    }

    @Test
    void publicationQueueIsBoundedAndRefusedWorkCanRetry() throws Exception {
        var backing = mock(JdbcImageCache.class);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(backing.sourcePublished(anyString())).thenAnswer(call -> {
            if (call.getArgument(0).equals("a")) {
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
            }
            return false;
        });
        when(backing.describe(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(true);
        var ticker = new AtomicLong();
        var serializedA = new AtomicInteger();
        var serializedB = new AtomicInteger();
        var serializedC = new AtomicInteger();
        try (var store = store(backing, ticker)) {
            for (String key : new String[] { "a", "b", "c" }) {
                store.getOrGenerate(key, () -> SVG);
            }
            store.describe("a", "formula", () -> {
                serializedA.incrementAndGet();
                return "{}";
            }, "a");
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                store.describe("b", "formula", () -> {
                    serializedB.incrementAndGet();
                    return "{}";
                }, "b");
                store.describe("c", "formula", () -> {
                    serializedC.incrementAndGet();
                    return "{}";
                }, "c");
                assertEquals(0, serializedA.get() + serializedB.get() + serializedC.get());
                assertEquals(SVG,
                    store.getOrGenerate("a", () -> fail("Blocked publication cannot block a hit")));
            } finally {
                finish.countDown();
            }
            await().atMost(Duration.ofSeconds(3)).until(() -> serializedB.get() == 1);
            assertEquals(0, serializedC.get(), "Only one queued publication is allowed");
            ticker.set(TimeUnit.SECONDS.toNanos(2));
            await().atMost(Duration.ofSeconds(3)).until(() -> {
                store.describe("c", "formula", () -> {
                    serializedC.incrementAndGet();
                    return "{}";
                }, "c");
                return serializedC.get() == 1;
            });
        }
    }

    @Test
    void transientPublicationFailureIsNotRememberedForever() throws Exception {
        var backing = mock(JdbcImageCache.class);
        var calls = new AtomicInteger();
        when(backing.sourcePublished("retry")).thenAnswer(_ -> {
            if (calls.incrementAndGet() == 1) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            }
            return false;
        });
        when(backing.describe(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(true);
        var ticker = new AtomicLong();
        var serialized = new AtomicInteger();
        try (var store = store(backing, ticker)) {
            store.getOrGenerate("retry", () -> SVG);
            store.describe("retry", "formula", () -> {
                serialized.incrementAndGet();
                return "{}";
            }, "x");
            await().atMost(Duration.ofSeconds(3)).until(() -> calls.get() == 1);
            for (int i = 0; i < 100; i++) {
                store.describe("retry", "formula", () -> fail("Failure must back off"), "x");
            }
            assertEquals(0, serialized.get());
            ticker.set(TimeUnit.SECONDS.toNanos(31));
            await().atMost(Duration.ofSeconds(3)).until(() -> {
                store.describe("retry", "formula", () -> {
                    serialized.incrementAndGet();
                    return "{}";
                }, "x");
                return serialized.get() == 1;
            });
        }
    }

    @Test
    void coldPublishedRowsSkipSerializationAndQueuedDeletionCannotResurrect() throws Exception {
        var backing = mock(JdbcImageCache.class);
        when(backing.findStored("stored")).thenReturn(new JdbcImageCache.Stored(SVG, true));
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(backing.sourcePublished("a")).thenAnswer(_ -> {
            entered.countDown();
            assertTrue(finish.await(5, TimeUnit.SECONDS));
            return false;
        });
        when(backing.describe(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(true);
        var ticker = new AtomicLong();
        try (var store = store(backing, ticker)) {
            store.getOrGenerate("stored", () -> fail("Must load stored image"));
            store.describe("stored", "formula", () -> fail("Stored metadata is already published"),
                "x");
            for (String key : new String[] { "a", "b", "c" }) {
                store.getOrGenerate(key, () -> SVG);
            }
            store.describe("a", "formula", () -> "{}", "a");
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                store.describe("b", "formula", () -> fail("Deleted job must be cancelled"), "b");
                store.delete("b");
            } finally {
                finish.countDown();
            }
            var finished = new AtomicBoolean();
            await().atMost(Duration.ofSeconds(3)).until(() -> {
                ticker.addAndGet(TimeUnit.SECONDS.toNanos(2));
                store.describe("c", "formula", () -> {
                    finished.set(true);
                    return "{}";
                }, "c");
                return finished.get();
            });
            verify(backing, never()).sourcePublished("b");
            verify(backing, never()).sourcePublished("stored");
            verify(backing).delete("b");
        }
    }

    @Test
    void coldReadBudgetFailsFastWhileWarmMemoryRemainsAvailable() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        var blocking = new AtomicBoolean();
        when(connection.createStatement()).thenReturn(mock(java.sql.Statement.class));
        var entered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        when(query.executeQuery()).thenAnswer(_ -> {
            if (blocking.get()
                && entered.getCount() > 0) {
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
            }
            return rows;
        });
        try (
            var backing = new JdbcImageCache(source, false, Duration.ofHours(1), 4096,
                Duration.ofDays(1), null);
            var store = store(backing, new AtomicLong());
            var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            store.getOrGenerate("warm", () -> SVG);
            clearInvocations(source);
            blocking.set(true);
            var first = callers.submit(() -> backing.find("cold-a"));
            var second = callers.submit(() -> backing.find("cold-b"));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var denied = assertThrows(ResponseStatusException.class,
                    () -> backing.find("cold-c"));
                assertEquals(503, denied.getStatusCode().value());
                assertEquals("1", denied.getHeaders().getFirst("Retry-After"));
                assertNull(backing.keyForToken("cold-c"));
                assertTrue(backing.library(null, 12).isEmpty());
                assertNull(backing.sourcePublished("cold-c"));
                assertEquals(SVG, store.getOrGenerate("warm",
                    () -> fail("Read capacity must not affect memory hits")));
                verify(source, times(5)).getConnection();
            } finally {
                finish.countDown();
            }
            assertNull(first.get(3, TimeUnit.SECONDS));
            assertNull(second.get(3, TimeUnit.SECONDS));
            assertNull(backing.find("after"));
        }
    }

    @Test
    void metadataHasItsOwnBoundAndDoesNotBlockImageWork() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        var rows = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        var entered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        when(query.executeQuery()).thenAnswer(_ -> {
            if (entered.getCount() > 0) {
                entered.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
            }
            return rows;
        });
        try (
            var backing = new JdbcImageCache(source, false, Duration.ofHours(1), 4096,
                Duration.ofDays(1), null);
            var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> backing.keyForToken("a"));
            var second = callers.submit(() -> backing.library(null, 12));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var denied = assertThrows(ResponseStatusException.class,
                    () -> backing.keyForToken("c"));
                assertEquals(503, denied.getStatusCode().value());
                assertNull(backing.find("image"));
            } finally {
                finish.countDown();
            }
            assertNull(first.get(3, TimeUnit.SECONDS));
            assertTrue(second.get(3, TimeUnit.SECONDS).isEmpty());
            assertNull(backing.keyForToken("after"));
        }
    }

    @Test
    void databaseFailuresKeepImageFallbackAndCheckedLibraryContracts() throws Exception {
        var source = mock(DataSource.class);
        var unavailable = new SQLException("unavailable", "08001");
        when(source.getConnection()).thenThrow(unavailable);
        try (var backing = new JdbcImageCache(source, false, Duration.ofHours(1), 4096,
            Duration.ofDays(1), null)) {
            assertNull(backing.find("image"));
            assertNull(backing.keyForToken("token"));
            assertSame(unavailable, assertThrows(SQLException.class,
                () -> backing.library(null, 12)));
            assertSame(unavailable, assertThrows(SQLException.class,
                () -> backing.libraryItem("item")));
            assertSame(unavailable, assertThrows(SQLException.class,
                () -> backing.delete("item")));
            var failure = assertThrows(ResponseStatusException.class,
                () -> backing.sourcePublished("item"));
            assertEquals(503, failure.getStatusCode().value());
        }
    }

    @Test
    void pendingVisitExpiresWhileWaitingForAConnection() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        when(source.getConnection()).thenAnswer(_ -> {
            Thread.sleep(75);
            return connection;
        });
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeQuery()).thenReturn(mock(ResultSet.class));
        try (var backing = new JdbcImageCache(source, false, Duration.ofMillis(20), 4096,
            Duration.ofDays(1), null, () -> false)) {
            backing.accessed("expired-visit");
            assertNull(backing.find("expired-visit"));
            verify(query).setBoolean(2, false);
        }
    }

    @Test
    void visitsAreDeduplicatedBoundedAndRetainedAfterBatchFailure() throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var query = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(query);
        when(query.executeBatch()).thenThrow(new SQLException("unavailable"))
            .thenReturn(new int[1024], new int[1]);
        try (var backing = new JdbcImageCache(source, false, Duration.ofHours(1), 4096,
            Duration.ofDays(1), null)) {
            for (int i = 0; i < 2000; i++) {
                backing.accessed("key-" + i);
            }
            for (int i = 0; i < 10_000; i++) {
                backing.accessed("key-0");
            }
            verifyNoInteractions(source);
            assertFalse(backing.flushTouches());
            verify(query, times(1024)).addBatch();
            clearInvocations(query);
            backing.accessed("overflow");
            assertTrue(backing.flushTouches());
            verify(query, times(1024)).addBatch();
            clearInvocations(query);
            backing.accessed("new-slot");
            assertTrue(backing.flushTouches());
            verify(query, times(1)).addBatch();
        }
    }

    @Test
    // The deadline resource installs and restores request-local state.
    @SuppressWarnings("try")
    void expiredRequestDeadlineDoesNotAcquireADatabaseConnection() throws Exception {
        var source = mock(DataSource.class);
        try (
            var backing = new JdbcImageCache(source, false, Duration.ofHours(1), 4096,
                Duration.ofDays(1), null);
            var deadline = WorkDeadline.open(Duration.ZERO)) {
            var failure = assertThrows(ResponseStatusException.class,
                () -> backing.find("expired"));
            assertEquals(504, failure.getStatusCode().value());
            verifyNoInteractions(source);
        }
    }
}
