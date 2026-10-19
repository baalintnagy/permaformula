package org.permaformula.rendering.cache;

import java.io.IOException;
import java.util.function.Supplier;

/** Storage boundary for generated SVGs, independent of HTTP responses and rendering engines. */
public interface GeneratedImageStore {
    /**
     * Returns an existing SVG or generates it once for concurrent callers of the same key. Failures
     * are not stored.
     */
    String getOrGenerate(String key, Generator generator) throws IOException;

    default void describe(String key, String kind, Supplier<String> descriptor, String excerpt) {
    }

    default void describe(String key, String kind, Supplier<String> descriptor, String excerpt,
        String tokenContext) {
        describe(key, kind, descriptor, excerpt);
    }

    default String findByToken(String tokenKey) {
        return null;
    }

    default void rememberToken(String tokenKey, String contentKey) {
    }

    @FunctionalInterface
    interface Generator {
        String generate() throws IOException;
    }
}
