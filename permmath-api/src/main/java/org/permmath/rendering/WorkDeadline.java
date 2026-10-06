package org.permmath.rendering;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A monotonic request deadline explicitly propagated to bounded work threads. */
public final class WorkDeadline implements AutoCloseable {
    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private final Long previous;

    private WorkDeadline(long deadline) {
        previous = CURRENT.get();
        CURRENT.set(previous == null ? deadline : Math.min(previous, deadline));
    }

    public static WorkDeadline open(Duration timeout) {
        return at(System.nanoTime() + timeout.toNanos());
    }

    static WorkDeadline at(long deadline) {
        return new WorkDeadline(deadline);
    }

    static long deadline(Duration fallback) {
        Long value = CURRENT.get();
        long limit = System.nanoTime() + fallback.toNanos();
        return value == null ? limit : Math.min(value, limit);
    }

    public static long remainingNanos(Duration maximum) {
        long remaining = Math.min(maximum.toNanos(), deadline(maximum) - System.nanoTime());
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) {
            throw timedOut();
        }
        return remaining;
    }

    static ResponseStatusException timedOut() {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "API request deadline exceeded");
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
