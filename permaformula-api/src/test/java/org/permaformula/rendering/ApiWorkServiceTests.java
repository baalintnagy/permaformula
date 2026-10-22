package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ApiWorkServiceTests {
    @Test
    void nestedValidationReusesPreparationWorkerWithoutReleasingItsSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var callers = Executors.newSingleThreadExecutor()) {
            var active = callers.submit(() -> work.prepare(() -> {
                Thread owner = Thread.currentThread();
                return work.prepare(() -> {
                    assertSame(owner, Thread.currentThread());
                    entered.countDown();
                    await(release);
                    return "validated";
                });
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> work.prepare(() -> "unexpected"))
                    .getStatusCode()
                    .value());
            } finally {
                release.countDown();
            }
            assertEquals("validated", active.get(2, TimeUnit.SECONDS));
        }
    }

    private final ApiWorkService work = new ApiWorkService(
        new ApiWorkProperties(1, 1, 1, Duration.ofSeconds(5)));

    @Test
    void occupiedGenerationRejectsImmediatelyButPreparationAndFetchRemainAvailable()
        throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var caller = Executors.newSingleThreadExecutor()) {
            var active = caller.submit(() -> work.generate(() -> {
                entered.countDown();
                await(release);
                return "done";
            }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                var overloaded = assertThrows(ResponseStatusException.class,
                    () -> work.generate(() -> "unexpected"));
                assertEquals(503, overloaded.getStatusCode()
                    .value());
                assertEquals("1", overloaded.getHeaders()
                    .getFirst("Retry-After"));
                assertEquals("prepare", work.prepare(() -> "prepare"));
                assertEquals("fetch", work.fetch(() -> "fetch"));
            } finally {
                release.countDown();
            }
            assertEquals("done", active.get(2, TimeUnit.SECONDS));
            assertEquals("recovered", work.generate(() -> "recovered"));
        }
    }

    @Test
    void failuresReleasePreparationAndFetchSlots() throws Exception {
        assertThrows(IOException.class, () -> work.prepare(() -> {
            throw new IOException("bad input");
        }));
        assertThrows(IllegalArgumentException.class, () -> work.fetch(() -> {
            throw new IllegalArgumentException("bad URL");
        }));
        assertEquals("ok", work.prepare(() -> "ok"));
        assertEquals("ok", work.fetch(() -> "ok"));
    }

    @Test
    void deadlinePropagatesThroughNestedFetchWithoutRestartingBudget() throws Exception {
        long deadline = System.nanoTime()
            + TimeUnit.SECONDS.toNanos(2);
        try (var _ = WorkDeadline.at(deadline)) {
            assertEquals(deadline, work
                .generate(() -> work.fetch(() -> WorkDeadline.deadline(Duration.ofSeconds(5)))));
        }
    }

    @Test
    void timeoutInterruptsWorkerAndRecoveryWaitsForItsExit() throws Exception {
        var worker = new AtomicReference<Thread>();
        try (var _ = WorkDeadline.open(Duration.ofMillis(100))) {
            assertEquals(504, assertThrows(ResponseStatusException.class, () -> work.prepare(() -> {
                worker.set(Thread.currentThread());
                await(new CountDownLatch(1));
                return "never";
            }))
                .getStatusCode()
                .value());
        }
        assertNotNull(worker.get());
        worker.get()
            .join(2000);
        assertFalse(worker.get()
            .isAlive());
        assertEquals("recovered", work.prepare(() -> "recovered"));
    }

    @Test
    void uninterruptibleWorkRetainsSlotAfterResponseTimeout() throws Exception {
        var worker = new AtomicReference<Thread>();
        var release = new CountDownLatch(1);
        try {
            try (var _ = WorkDeadline.open(Duration.ofMillis(100))) {
                assertEquals(504,
                    assertThrows(ResponseStatusException.class, () -> work.fetch(() -> {
                        worker.set(Thread.currentThread());
                        while (release.getCount() != 0) {
                            try {
                                release.await();
                            } catch (InterruptedException _) {
                            }
                        }
                        return "late";
                    }))
                        .getStatusCode()
                        .value());
            }
            assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> work.fetch(() -> "must not start"))
                .getStatusCode()
                .value());
        } finally {
            release.countDown();
            if (worker.get() != null) {
                worker.get()
                    .join(2000);
            }
        }
        assertEquals("recovered", work.fetch(() -> "recovered"));
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }
}
