package org.permaformula.rendering;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Elapsed scope with no content or URL attributes. */
final class GenerationTiming implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GenerationTiming.class);
    private final String stage;
    private final long started = System.nanoTime();

    GenerationTiming(String stage) {
        this.stage = stage;
    }

    @Override
    public void close() {
        log.info("event=render_timing stage={} pid={} durationMs={}", stage,
            ProcessHandle.current().pid(),
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
