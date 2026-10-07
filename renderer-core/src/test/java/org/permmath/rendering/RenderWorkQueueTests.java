package org.permmath.rendering;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.permmath.contract.v1.*;

import com.google.protobuf.ByteString;

@Timeout(15)
class RenderWorkQueueTests {
    static RenderRequest request(String id) {
        return RenderRequest.newBuilder()
            .setRequestId(id)
            .setFormula(AsciiMathFormula.newBuilder().setFormula("x"))
            .build();
    }

    static RenderResponse response(RenderRequest request) {
        return RenderResponse.newBuilder()
            .setRequestId(request.getRequestId())
            .setSvg(ByteString.copyFromUtf8("<svg/>"))
            .build();
    }

    static RenderQueueSettings settings(int capacity, Duration wait) {
        return new RenderQueueSettings(capacity, 64 * 1024 * 1024, wait);
    }

    static void awaitLatch(CountDownLatch latch) throws RenderException {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RenderException(RenderException.Kind.CANCELLED, "cancelled", failure);
        }
    }

    static RenderException.Kind failureKind(Future<?> future) throws Exception {
        var failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
        return assertInstanceOf(RenderException.class, failure.getCause()).kind();
    }

    @Test
    void fixedWorkerProcessesFifoAndWaitingConsumesTheOriginalDeadline() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new CopyOnWriteArrayList<String>();
        var secondBudget = new AtomicReference<Duration>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var queue = new RenderWorkQueue(1, settings(2, Duration.ofSeconds(5)), (request, budget) -> {
                    assertTrue(Thread.currentThread().getName().startsWith("renderer-work-"));
                    order.add(request.getRequestId());
                    if (request.getRequestId().equals("first")) {
                        entered.countDown();
                        awaitLatch(release);
                    }
                    else {
                        secondBudget.set(budget);
                    }
                    return response(request);
                })) {
            var first = callers.submit(() -> queue.execute(request("first"), Duration.ofSeconds(10)));
            try {
                awaitLatch(entered);
                var second = callers.submit(() -> queue.execute(request("second"), Duration.ofSeconds(10)));
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.waitingJobs() == 1);
                var third = callers.submit(() -> queue.execute(request("third"), Duration.ofSeconds(10)));
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.waitingJobs() == 2);
                assertEquals(RenderException.Kind.OVERLOADED, assertThrows(RenderException.class,
                        () -> queue.execute(request("full"), Duration.ofSeconds(1))).kind());
                assertEquals(List.of("first"), order);
                Thread.sleep(50);
                release.countDown();
                assertEquals("first", first.get(3, TimeUnit.SECONDS).getRequestId());
                assertEquals("second", second.get(3, TimeUnit.SECONDS).getRequestId());
                assertEquals("third", third.get(3, TimeUnit.SECONDS).getRequestId());
                assertEquals(List.of("first", "second", "third"), order);
                assertTrue(secondBudget.get().compareTo(Duration.ofMillis(9950)) < 0);
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.retainedBytes() == 0);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void queuedExpiryRemovesPayloadAndNeverExecutesOrInterruptsActiveJob() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var queue = new RenderWorkQueue(1, settings(1, Duration.ofMillis(100)), (request, _) -> {
                    executions.incrementAndGet();
                    entered.countDown();
                    awaitLatch(release);
                    return response(request);
                })) {
            var first = callers.submit(() -> queue.execute(request("active"), Duration.ofSeconds(5)));
            try {
                awaitLatch(entered);
                long activeBytes = queue.retainedBytes();
                var expired = callers.submit(() -> queue.execute(request("expired"), Duration.ofSeconds(5)));
                assertEquals(RenderException.Kind.QUEUE_DEADLINE, failureKind(expired));
                assertEquals(0, queue.waitingJobs());
                assertEquals(activeBytes, queue.retainedBytes());
                assertFalse(first.isDone());
                // Capacity released by expiry accepts another job while the original is still active.
                var next = callers.submit(() -> queue.execute(request("next"), Duration.ofSeconds(5)));
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.waitingJobs() == 1);
                release.countDown();
                first.get(3, TimeUnit.SECONDS);
                next.get(3, TimeUnit.SECONDS);
                assertEquals(2, executions.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void requestDeadlineExpiresBeforeLongerQueueWait() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var queue = new RenderWorkQueue(1, settings(1, Duration.ofSeconds(5)), (request, _) -> {
                    entered.countDown();
                    awaitLatch(release);
                    return response(request);
                })) {
            var active = callers.submit(() -> queue.execute(request("active"), Duration.ofSeconds(5)));
            try {
                awaitLatch(entered);
                var expired = callers.submit(() -> queue.execute(request("short"), Duration.ofMillis(100)));
                assertEquals(RenderException.Kind.QUEUE_DEADLINE, failureKind(expired));
                assertEquals(0, queue.waitingJobs());
                assertFalse(active.isDone());
            } finally {
                release.countDown();
                active.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void cancelledQueuedCallerCannotExecuteLaterAndReleasesAdmission() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var cancellation = new AtomicReference<RenderException.Kind>();
        var callerDone = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var queue = new RenderWorkQueue(1, settings(1, Duration.ofSeconds(5)), (request, _) -> {
                    executions.incrementAndGet();
                    entered.countDown();
                    awaitLatch(release);
                    return response(request);
                })) {
            var active = callers.submit(() -> queue.execute(request("active"), Duration.ofSeconds(5)));
            try {
                awaitLatch(entered);
                var cancelled = callers.submit(() -> {
                    try {
                        queue.execute(request("cancelled"), Duration.ofSeconds(5));
                    }
                    catch (RenderException failure) {
                        cancellation.set(failure.kind());
                    }
                    finally {
                        callerDone.countDown();
                    }
                });
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.waitingJobs() == 1);
                cancelled.cancel(true);
                awaitLatch(callerDone);
                assertEquals(RenderException.Kind.QUEUE_CANCELLED, cancellation.get());
                assertEquals(0, queue.waitingJobs());
                release.countDown();
                active.get(3, TimeUnit.SECONDS);
                queue.execute(request("recovered"), Duration.ofSeconds(5));
                assertEquals(2, executions.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void runningCancellationRetainsMemoryAndWorkerUntilActualExit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var queue = new RenderWorkQueue(1, settings(0, Duration.ofSeconds(5)), (request, _) -> {
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            interrupted.countDown();
                        }
                    }
                    return response(request);
                })) {
            var active = callers.submit(() -> queue.execute(request("active"), Duration.ofSeconds(5)));
            try {
                awaitLatch(entered);
                long retained = queue.retainedBytes();
                active.cancel(true);
                awaitLatch(interrupted);
                assertEquals(retained, queue.retainedBytes());
                assertEquals(RenderException.Kind.OVERLOADED, assertThrows(RenderException.class,
                        () -> queue.execute(request("not-yet"), Duration.ofSeconds(1))).kind());
                release.countDown();
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.retainedBytes() == 0);
                assertEquals("recovered", queue.execute(request("recovered"), Duration.ofSeconds(1)).getRequestId());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void byteBudgetIncludesRunningRequestsAndRejectsBeforeExecution() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        long weight = 1024L + 3L * request("same").getSerializedSize();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor();
                var queue = new RenderWorkQueue(1, new RenderQueueSettings(10, weight * 2 - 1, Duration.ofSeconds(5)), (request, _) -> {
                    entered.countDown();
                    awaitLatch(release);
                    return response(request);
                })) {
            var first = callers.submit(() -> queue.execute(request("same"), Duration.ofSeconds(5)));
            try {
                awaitLatch(entered);
                assertEquals(RenderException.Kind.OVERLOADED, assertThrows(RenderException.class,
                        () -> queue.execute(request("same"), Duration.ofSeconds(5))).kind());
                assertEquals(0, queue.waitingJobs());
                assertEquals(weight, queue.retainedBytes());
            } finally {
                release.countDown();
                first.get(3, TimeUnit.SECONDS);
            }
            await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(5))
                .atMost(Duration.ofSeconds(3))
                .until(() -> queue.retainedBytes() == 0);
        }
    }

    @Test
    void invalidInputAndWorkerFailureReleaseAdmission() throws Exception {
        try (var queue = new RenderWorkQueue(1, settings(1, Duration.ofSeconds(5)), (request, _) -> {
            if (request.getRequestId().equals("fail")) {
                throw new RenderException(RenderException.Kind.FAILED, "failure");
            }
            return response(request);
        })) {
            assertEquals(RenderException.Kind.INVALID, assertThrows(RenderException.class,
                    () -> queue.execute(RenderRequest.getDefaultInstance(), Duration.ofSeconds(5))).kind());
            assertEquals(0, queue.retainedBytes());
            assertEquals(RenderException.Kind.FAILED, assertThrows(RenderException.class,
                    () -> queue.execute(request("fail"), Duration.ofSeconds(5))).kind());
            await()
                .pollDelay(Duration.ZERO)
                .pollInterval(Duration.ofMillis(5))
                .atMost(Duration.ofSeconds(3))
                .until(() -> queue.retainedBytes() == 0);
            assertEquals("ok", queue.execute(request("ok"), Duration.ofSeconds(5)).getRequestId());
        }
    }

    @Test
    void shutdownRemovesQueuedRequestsAndWakesCallers() throws Exception {
        var entered = new CountDownLatch(1);
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var queue = new RenderWorkQueue(1, settings(1, Duration.ofSeconds(5)), (request, _) -> {
                entered.countDown();
                awaitLatch(new CountDownLatch(1));
                return response(request);
            });
            try {
                var active = callers.submit(() -> queue.execute(request("active"), Duration.ofSeconds(5)));
                awaitLatch(entered);
                var waiting = callers.submit(() -> queue.execute(request("waiting"), Duration.ofSeconds(5)));
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.waitingJobs() == 1);
                queue.close();
                assertEquals(RenderException.Kind.QUEUE_CANCELLED, failureKind(waiting));
                assertEquals(RenderException.Kind.CANCELLED, failureKind(active));
                await()
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(5))
                    .atMost(Duration.ofSeconds(3))
                    .until(() -> queue.retainedBytes() == 0);
                assertEquals(RenderException.Kind.OVERLOADED, assertThrows(RenderException.class,
                        () -> queue.execute(request("closed"), Duration.ofSeconds(5))).kind());
                assertEquals(0, queue.retainedBytes());
            } finally {
                queue.close();
            }
        }
    }

    @Test
    void configurationIsValidatedAndPayloadBudgetTracksHeapHeadroom() {
        var defaults = RenderQueueSettings.fromEnvironment(Map.of());
        assertEquals(16, defaults.capacity());
        assertEquals(Duration.ofSeconds(15), defaults.maxWait());
        assertEquals(Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4), defaults.effectiveRetainedBytes());
        assertEquals(Runtime.getRuntime().maxMemory() / 4,
                new RenderQueueSettings(16, Long.MAX_VALUE, Duration.ofSeconds(15)).effectiveRetainedBytes());
        assertThrows(IllegalArgumentException.class, () -> new RenderQueueSettings(-1, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RenderQueueSettings(1025, 1, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RenderQueueSettings(16, 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new RenderQueueSettings(16, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RenderQueueSettings(16, 1, Duration.ofSeconds(61)));
    }
}
