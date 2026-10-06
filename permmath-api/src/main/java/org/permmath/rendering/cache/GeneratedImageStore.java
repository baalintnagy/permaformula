package org.permmath.rendering.cache;

import java.io.IOException;

/** Storage boundary for generated SVGs, independent of HTTP responses and rendering engines. */
public interface GeneratedImageStore {
    /** Returns an existing SVG or generates it once for concurrent callers of the same key. Failures are not stored. */
    String getOrGenerate(String key, Generator generator) throws IOException;

    @FunctionalInterface
    interface Generator {
        String generate() throws IOException;
    }
}
