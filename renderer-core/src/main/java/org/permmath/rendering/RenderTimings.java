package org.permmath.rendering;

import java.util.concurrent.TimeUnit;

/** Content-free elapsed timings; stdout is reserved for the worker protocol. */
final class RenderTimings {
    static void elapsed(String stage, long started) {
        System.err.println("event=render_timing stage=" + stage + " pid=" + ProcessHandle.current().pid()
                + " durationMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private RenderTimings() {}
}
