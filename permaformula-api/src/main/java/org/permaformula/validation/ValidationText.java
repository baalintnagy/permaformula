package org.permaformula.validation;

import static org.permaformula.validation.QuickValidator.validate;

import java.nio.charset.StandardCharsets;

import com.google.common.base.Utf8;

/** Shared UTF-8 accounting for request and resource bounds. */
public final class ValidationText {
    private ValidationText() {
    }

    public static void text(String value, int maximumBytes, String name) {
        // Reject excessive character counts before measuring encoded bytes.
        validate(
            value == null
                || value.length() <= maximumBytes
                    && utf8Length(value) <= maximumBytes,
            "%s exceeds %s UTF-8 bytes".formatted(name, maximumBytes));
    }

    /** Count bytes without allocating an encoded array for well-formed UTF-16. */
    public static int utf8Length(String value) {
        try {
            return Utf8.encodedLength(value);
        } catch (IllegalArgumentException _) {
            // Preserve the JDK encoder's replacement behavior for unpaired surrogates.
            return value.getBytes(StandardCharsets.UTF_8).length;
        }
    }

}
