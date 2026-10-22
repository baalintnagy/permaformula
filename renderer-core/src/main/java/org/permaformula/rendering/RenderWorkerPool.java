package org.permaformula.rendering;

import static org.permaformula.validation.QuickValidator.validate;

import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.contract.v1.RenderRequest;
import org.permaformula.contract.v1.RenderResponse;

/** Each slot owns one sequential process. Initialization runs before requests arrive. */
final class RenderWorkerPool implements AutoCloseable {
    static final int READY = 82;
    static final int OK = 0;
    static final int INVALID = 1;
    private final BlockingQueue<Slot> slots;
    private final Map<Process, Set<ProcessHandle>> processes = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newVirtualThreadPerTaskExecutor();
    private final Supplier<ProcessBuilder> factory;
    private final Duration startupTimeout;
    private final int maxJobs;
    private volatile boolean closed;

    RenderWorkerPool(int capacity, Supplier<ProcessBuilder> factory, Duration startupTimeout,
        int maxJobs) {
        validate(
            !(capacity < 1
                || maxJobs < 1),
            () -> new IllegalArgumentException("Invalid worker pool size or lifetime"));
        this.factory = factory;
        this.startupTimeout = startupTimeout;
        this.maxJobs = maxJobs;
        slots = new ArrayBlockingQueue<>(capacity);
        for (int i = 0; i < capacity; i++) {
            slots.add(new Slot());
        }
    }

    private final class Slot {
        boolean aborted;
        Process process;
        final Future<Worker> ready = io.submit(() -> start(this));
    }

    private Worker start(Slot slot) throws Exception {
        long started = System.nanoTime();
        Process process;
        synchronized (this) {
            if (closed) {
                throw new IOException("Worker pool closed");
            }
            synchronized (slot) {
                if (slot.aborted) {
                    throw new IOException("Worker initialization cancelled");
                }
                process = factory.get()
                    .start();
                slot.process = process;
                processes.put(process, ConcurrentHashMap.newKeySet());
            }
        }
        boolean ready = false;
        Future<Integer> handshake = io.submit(() -> process.getInputStream()
            .read());
        try {
            if (handshake.get(startupTimeout.toNanos(), TimeUnit.NANOSECONDS) != READY
                || !process.isAlive()) {
                throw new IOException("Worker initialization failed");
            }
            ready = true;
            rememberDescendants(process);
            RenderTimings.elapsed("worker_startup", started);
            return new Worker(process);
        } catch (Exception failure) {
            System.err.println(
                "event=render_worker_startup_failed type=%s".formatted(
                    failure.getClass()
                        .getSimpleName()));
            throw failure;
        } finally {
            if (!ready) {
                stop(process);
            }
            handshake.cancel(true);
        }
    }

    RenderResponse execute(RenderRequest request, Duration budget) throws RenderException {
        long deadline = System.nanoTime()
            + budget.toNanos();
        long started = System.nanoTime();
        Slot slot = null;
        Worker worker = null;
        boolean healthy = false;
        Future<RenderResponse> exchange = null;
        try {
            if (closed) {
                throw new IOException("Worker pool closed");
            }
            slot = slots.poll(remaining(deadline), TimeUnit.NANOSECONDS);
            if (slot == null) {
                throw new TimeoutException();
            }
            worker = slot.ready.get(remaining(deadline), TimeUnit.NANOSECONDS);
            RenderTimings.elapsed("worker_acquire", started);
            Worker active = worker;
            exchange = io.submit(() -> active.exchange(request));
            RenderResponse response = exchange.get(remaining(deadline), TimeUnit.NANOSECONDS);
            healthy = ++worker.jobs < maxJobs
                && worker.process.isAlive();
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread()
                .interrupt();
            throw new RenderException(RenderException.Kind.CANCELLED, "Rendering cancelled",
                interrupted);
        } catch (TimeoutException expired) {
            throw new RenderException(RenderException.Kind.DEADLINE, "Render deadline exceeded",
                expired);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RenderException render) {
                throw render;
            }
            throw new RenderException(RenderException.Kind.FAILED, "Render worker failed",
                failed.getCause());
        } catch (IOException | RejectedExecutionException failed) {
            throw new RenderException(RenderException.Kind.FAILED, "Render worker unavailable",
                failed);
        } finally {
            // Kill before releasing admission or cancelling readers. No stale response can enter
            // the next job.
            if (slot != null) {
                if (!healthy
                    || closed) {
                    Process retiring;
                    synchronized (slot) {
                        slot.aborted = true;
                        retiring = slot.process;
                    }
                    if (retiring != null) {
                        stop(retiring);
                    }
                    slot.ready.cancel(true);
                    synchronized (this) {
                        if (!closed) {
                            slot = new Slot();
                        }
                    }
                }
                if (!closed) {
                    slots.offer(slot);
                }
            }
            if (exchange != null) {
                exchange.cancel(true);
            }
            RenderTimings.elapsed("worker_roundtrip", started);
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException();
        }
        return remaining;
    }

    private final class Worker {
        final Process process;
        int jobs;

        Worker(Process process) {
            this.process = process;
        }

        RenderResponse exchange(RenderRequest request) throws IOException, RenderException {
            rememberDescendants(process);
            WorkerMessages.write(process.getOutputStream(), request);
            int status = process.getInputStream()
                .read();
            if (status == INVALID) {
                throw new RenderException(RenderException.Kind.INVALID,
                    "Invalid rendering content");
            }
            if (status != OK) {
                throw new IOException("Invalid worker status");
            }
            RenderResponse response = WorkerMessages.read(process.getInputStream(),
                RenderResponse.parser());
            if (!request.getRequestId()
                .equals(response.getRequestId())
                || response.getSvg()
                    .isEmpty()
                || response.getSvg()
                    .size() > ComplexityPolicy.CURRENT.get(ComplexityPolicy.Limit.SVG_BYTES)) {
                throw new IOException("Invalid worker response");
            }
            return response;
        }
    }

    private void rememberDescendants(Process process) {
        Set<ProcessHandle> known = processes.get(process);
        if (known != null) {
            process.descendants()
                .forEach(known::add);
        }
    }

    private void stop(Process process) {
        synchronized (process) {
            boolean interrupted = Thread.interrupted();
            try {
                HashSet<ProcessHandle> children = new HashSet<ProcessHandle>();
                Set<ProcessHandle> known = processes.get(process);
                if (known != null) {
                    known.stream()
                        .filter(ProcessHandle::isAlive)
                        .forEach(children::add);
                    // Retained native parents can still expose descendants after the JVM has
                    // crashed.
                    known.stream()
                        .filter(ProcessHandle::isAlive)
                        .forEach(child -> child.descendants()
                            .forEach(children::add));
                }
                process.descendants()
                    .forEach(children::add);
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                validate(
                    process.waitFor(5, TimeUnit.SECONDS),
                    () -> new IllegalStateException("Worker did not exit"));
                for (var child : children) {
                    try {
                        child.onExit()
                            .get(5, TimeUnit.SECONDS);
                    } catch (ExecutionException | TimeoutException failure) {
                        throw new IllegalStateException("Worker descendant did not exit", failure);
                    }
                }
                process.getInputStream()
                    .close();
                process.getOutputStream()
                    .close();
                processes.remove(process);
            } catch (IOException failure) {
                throw new IllegalStateException("Worker pipes did not close", failure);
            } catch (InterruptedException failure) {
                Thread.currentThread()
                    .interrupt();
                throw new IllegalStateException("Worker cleanup interrupted", failure);
            } finally {
                if (interrupted) {
                    Thread.currentThread()
                        .interrupt();
                }
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        RuntimeException failure = null;
        try {
            for (var process : List.copyOf(processes.keySet())) {
                try {
                    stop(process);
                } catch (RuntimeException failed) {
                    if (failure == null) {
                        failure = failed;
                    } else {
                        failure.addSuppressed(failed);
                    }
                }
            }
        } finally {
            io.shutdownNow();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
