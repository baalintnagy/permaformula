package org.permaformula.validation;

/** Request size constants shared by parsers and constraints. */
public final class InputLimits {
    public static final int MAX_CONTENT_BYTES = 6 * 1024 * 1024;
    public static final int MAX_STYLE_BYTES = 1536 * 1024;
    public static final int MAX_FORMULA_BYTES = 384 * 1024;
    public static final int MAX_JSON_BYTES = 12 * 1024 * 1024;
    public static final int MAX_COMPRESSED_BYTES = MAX_JSON_BYTES + 16384;
    public static final int MAX_ENCODED_CHARS = 4 * ((MAX_COMPRESSED_BYTES + 2) / 3);
    public static final int MAX_STYLE_REFS = 8;
    public static final int MAX_REF_BYTES = 2048;

    private InputLimits() {
    }
}
