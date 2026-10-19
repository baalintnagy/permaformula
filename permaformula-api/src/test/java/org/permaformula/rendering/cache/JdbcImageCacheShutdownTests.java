package org.permaformula.rendering.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JdbcImageCacheShutdownTests {
    @Test
    void interruptedMaintenanceStillFlushesAndClosesOwner() throws Exception {
        var owner = mock(AutoCloseable.class);
        var maintenance = mock(ScheduledExecutorService.class);
        var cache = cache(owner, maintenance);
        when(maintenance.awaitTermination(5, TimeUnit.SECONDS))
            .thenThrow(new InterruptedException("stopping"));
        try {
            var failure = assertThrows(IOException.class, cache::close);
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            var order = inOrder(cache, owner);
            order.verify(cache).flushTouches();
            order.verify(owner).close();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedOwnerRestoresInterruptFlag() throws Exception {
        var owner = mock(AutoCloseable.class);
        var cache = cache(owner, mock(ScheduledExecutorService.class));
        doThrow(new InterruptedException("owner")).when(owner).close();
        try {
            var failure = assertThrows(IOException.class, cache::close);
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(cache).flushTouches();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ownerFailureIsSuppressedWhenMaintenanceWasInterrupted() throws Exception {
        var owner = mock(AutoCloseable.class);
        var maintenance = mock(ScheduledExecutorService.class);
        var cache = cache(owner, maintenance);
        when(maintenance.awaitTermination(5, TimeUnit.SECONDS))
            .thenThrow(new InterruptedException("stopping"));
        var ownerFailure = new SQLException("owner");
        doThrow(ownerFailure).when(owner).close();
        try {
            var failure = assertThrows(IOException.class, cache::close);
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(1, failure.getSuppressed().length);
            assertSame(ownerFailure, failure.getSuppressed()[0].getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(cache).flushTouches();
        } finally {
            Thread.interrupted();
        }
    }

    private JdbcImageCache cache(AutoCloseable owner, ScheduledExecutorService maintenance) {
        var cache = spy(new JdbcImageCache(mock(DataSource.class), false, Duration.ofHours(1),
            4096, Duration.ofDays(1), owner));
        var original = assertInstanceOf(ScheduledExecutorService.class,
            ReflectionTestUtils.getField(cache, "maintenance"));
        original.shutdownNow();
        ReflectionTestUtils.setField(cache, "maintenance", maintenance);
        return cache;
    }
}
