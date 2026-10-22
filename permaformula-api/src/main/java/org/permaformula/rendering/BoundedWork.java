package org.permaformula.rendering;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** No queue; slots are held until the actual thread exits, including after cancellation. */
final class BoundedWork {
    private final Semaphore slots;
    private final String name;
    private final Duration timeout;
    private final ThreadLocal<Boolean> active = new ThreadLocal<>();

    BoundedWork(String name, int concurrency, Duration timeout) {
        this.name = name;
        this.timeout = timeout;
        slots = new Semaphore(concurrency);
    }

    <T> T execute(ApiWorkService.Operation<T> operation) throws IOException {
        long deadline = WorkDeadline.deadline(timeout);
        WorkDeadline.remainingNanos(timeout);
        if (Boolean.TRUE.equals(active.get())) {
            T result = operation.run();
            WorkDeadline.remainingNanos(timeout);
            return result;
        }
        if (!slots.tryAcquire()) {
            throw new WorkOverloadedException("%s capacity is occupied".formatted(name), null);
        }
        AtomicBoolean ran = new AtomicBoolean();
        FutureTask<T> task = new FutureTask<T>(() -> {
            ran.set(true);
            active.set(true);
            try (WorkDeadline _ = WorkDeadline.at(deadline)) {
                WorkDeadline.remainingNanos(timeout);
                T result = operation.run();
                WorkDeadline.remainingNanos(timeout);
                return result;
            } finally {
                active.remove();
                slots.release();
            }
        });
        Thread worker = Thread.ofPlatform()
            .daemon(true)
            .name("api-%s".formatted(name))
            .unstarted(() -> {
                try {
                    task.run();
                } finally {
                    if (!ran.get()) {
                        slots.release();
                    }
                }
            });
        try {
            worker.start();
        } catch (RuntimeException | Error failure) {
            slots.release();
            throw failure;
        }
        try {
            return task.get(Math.max(1, deadline - System.nanoTime()),
                TimeUnit.NANOSECONDS);
        } catch (TimeoutException _) {
            task.cancel(true);
            throw WorkDeadline.timedOut();
        } catch (InterruptedException _) {
            task.cancel(true);
            Thread.currentThread()
                .interrupt();
            throw WorkDeadline.timedOut();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException checked) {
                throw checked;
            }
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            throw new IOException("API work failed", failed.getCause());
        }
    }
}
