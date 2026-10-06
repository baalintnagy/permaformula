package org.permmath.rendering;

import java.time.Duration;
import java.util.concurrent.*;
import org.permmath.contract.v1.RenderRequest;
import org.permmath.contract.v1.RenderResponse;

/** Fixed workers and bounded admission. Cancellation never frees a running worker early. */
public final class RenderWorkQueue implements RenderJobExecutor, AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final Semaphore outstanding;
    private final RenderJobExecutor jobs;
    private final RenderQueueSettings settings;
    private final long byteLimit;
    private long retainedBytes;

    public RenderWorkQueue(int concurrency, RenderQueueSettings settings, RenderJobExecutor jobs) {
        if (concurrency < 1 || concurrency > 64) throw new IllegalArgumentException("Renderer concurrency must be 1..64");
        this.settings = java.util.Objects.requireNonNull(settings);
        this.jobs = java.util.Objects.requireNonNull(jobs);
        byteLimit = settings.effectiveRetainedBytes();
        outstanding = new Semaphore(concurrency + settings.capacity());
        BlockingQueue<Runnable> waiting = settings.capacity() == 0 ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(settings.capacity());
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.SECONDS, waiting,
                Thread.ofPlatform().daemon(true).name("renderer-work-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public int maxOutstanding() { return executor.getMaximumPoolSize() + settings.capacity(); }
    public long effectiveRetainedBytes() { return byteLimit; }
    synchronized long retainedBytes() { return retainedBytes; }
    int waitingJobs() { return executor.getQueue().size(); }

    @Override
    public RenderResponse execute(RenderRequest request, Duration budget) throws RenderException {
        if (Thread.currentThread().isInterrupted()) throw new RenderException(RenderException.Kind.QUEUE_CANCELLED, "Rendering cancelled before admission");
        if (budget.isNegative() || budget.isZero()) throw queueExpired();
        // Clamp direct callers too; service callers supply the remaining RPC budget.
        long budgetNanos = (budget.compareTo(Duration.ofMinutes(5)) > 0 ? Duration.ofMinutes(5) : budget).toNanos();
        long now = System.nanoTime();
        long deadline = now + budgetNanos;
        long queueDeadline = now + Math.min(budgetNanos, settings.maxWait().toNanos());
        // Conservative payload accounting includes protobuf bytes and UTF-16 strings.
        // Charge running requests too, so dequeue/cancellation cannot create extra memory headroom.
        long weight = 1024L + 3L * request.getSerializedSize();
        if (!reserve(weight)) throw overloaded();
        Job task;
        try {
            RenderLimits.validate(request);
            task = new Job(request, weight, deadline, queueDeadline);
        } catch (RenderException | RuntimeException | Error failure) {
            release(weight);
            throw failure;
        }
        try {
            executor.execute(task);
        } catch (RejectedExecutionException full) {
            task.cancelWaiting();
            throw overloaded();
        } catch (RuntimeException | Error failure) {
            task.cancelWaiting();
            throw failure;
        }
        try {
            try {
                task.started.get(Math.max(1, queueDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException expired) {
                // A worker may have started within the budget just as the waiter woke.
                if (task.cancelWaiting()) throw queueExpired();
            }
            return task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (InterruptedException cancelled) {
            boolean waiting = task.cancelWaiting();
            if (!waiting) task.cancel(true);
            Thread.currentThread().interrupt();
            throw new RenderException(waiting ? RenderException.Kind.QUEUE_CANCELLED : RenderException.Kind.CANCELLED,
                    "Rendering cancelled", cancelled);
        } catch (TimeoutException expired) {
            boolean waiting = task.cancelWaiting();
            if (!waiting) task.cancel(true);
            throw new RenderException(waiting ? RenderException.Kind.QUEUE_DEADLINE : RenderException.Kind.DEADLINE,
                    "Render deadline exceeded", expired);
        } catch (CancellationException cancelled) {
            throw new RenderException(RenderException.Kind.QUEUE_CANCELLED, "Rendering stopped before execution", cancelled);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RenderException rendering) throw rendering;
            throw new RenderException(RenderException.Kind.FAILED, "Render worker failed", failed.getCause());
        }
    }

    private synchronized boolean reserve(long weight) {
        if (weight > byteLimit - retainedBytes || !outstanding.tryAcquire()) return false;
        retainedBytes += weight;
        return true;
    }

    private synchronized void release(long weight) {
        retainedBytes -= weight;
        outstanding.release();
    }

    private static RenderException overloaded() {
        return new RenderException(RenderException.Kind.OVERLOADED, "Renderer queue capacity is occupied");
    }

    private static RenderException queueExpired() {
        return new RenderException(RenderException.Kind.QUEUE_DEADLINE, "Renderer queue wait expired");
    }

    private final class Job extends FutureTask<RenderResponse> {
        final CompletableFuture<Void> started = new CompletableFuture<>();
        private final long weight;
        private final long queueDeadline;
        private boolean running;
        private boolean released;

        Job(RenderRequest request, long weight, long deadline, long queueDeadline) {
            super(() -> {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw queueExpired();
                return jobs.execute(request, Duration.ofNanos(remaining));
            });
            this.weight = weight;
            this.queueDeadline = queueDeadline;
        }

        @Override public void run() {
            synchronized (this) {
                if (isCancelled()) { releaseOnce(); return; }
                if (queueDeadline - System.nanoTime() <= 0) {
                    setException(queueExpired());
                    started.complete(null);
                    releaseOnce();
                    return;
                }
                running = true;
                started.complete(null);
            }
            try { super.run(); }
            finally { synchronized (this) { releaseOnce(); } }
        }

        synchronized boolean cancelWaiting() {
            if (running) return false;
            cancel(false);
            executor.remove(this);
            releaseOnce();
            started.complete(null);
            return true;
        }

        private void releaseOnce() {
            if (!released) { released = true; release(weight); }
        }
    }

    @Override public void close() {
        for (Runnable task : executor.shutdownNow()) ((Job) task).cancelWaiting();
    }
}
