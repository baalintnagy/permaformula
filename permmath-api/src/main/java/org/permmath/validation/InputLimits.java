package org.permmath.validation;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.google.common.base.Utf8;

/** Cheap bounds applied before serialization, parsing or rendering. */
public final class InputLimits {
    public static final int MAX_CONTENT_BYTES = 4 * 1024 * 1024;
    public static final int MAX_STYLE_BYTES = 1024 * 1024;
    public static final int MAX_FORMULA_BYTES = 256 * 1024;
    public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    public static final int MAX_COMPRESSED_BYTES = MAX_JSON_BYTES + 16384;
    public static final int MAX_ENCODED_CHARS = 4 * ((MAX_COMPRESSED_BYTES + 2) / 3);
    public static final int MAX_STYLE_REFS = 8;
    public static final int MAX_REF_BYTES = 2048;

    private InputLimits() { }

    public static void text(String value, int maximumBytes, String name) {
        // Reject excessive character counts before measuring encoded bytes.
        if (value != null && (value.length() > maximumBytes
                || utf8Length(value) > maximumBytes)) {
            throw new IllegalArgumentException(name + " exceeds " + maximumBytes + " UTF-8 bytes");
        }
    }

    /** Count bytes without allocating an encoded array for well-formed UTF-16. */
    public static int utf8Length(String value) {
        try {
            return Utf8.encodedLength(value);
        } catch (IllegalArgumentException malformedUtf16) {
            // Preserve the JDK encoder's replacement behavior for unpaired surrogates.
            return value.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    public static void formula(String value) {
        text(value, MAX_FORMULA_BYTES, "Formula");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Formula must not be blank");
        }
    }

    public static void descriptor(String content, Integer width, List<String> refs, String style) {
        text(content, MAX_CONTENT_BYTES, "Content");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Content must not be blank");
        }
        if (width != null && (width < 220 || width > 4000)) {
            throw new IllegalArgumentException("Image width must be between 220 and 4000");
        }
        text(style, MAX_STYLE_BYTES, "Style");
        if (refs != null) {
            if (refs.size() > MAX_STYLE_REFS) {
                throw new IllegalArgumentException("At most eight stylesheet references are allowed");
            }
            refs.forEach(ref -> {
                text(ref, MAX_REF_BYTES, "Stylesheet reference");
                if (ref == null || ref.isBlank()) {
                    throw new IllegalArgumentException("Stylesheet references must not be blank");
                }
            });
        }
    }
}
