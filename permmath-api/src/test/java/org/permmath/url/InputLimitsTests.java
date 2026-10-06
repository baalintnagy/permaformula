package org.permmath.url;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.permmath.validation.InputLimits;

class InputLimitsTests {
    @Test
    void descriptorAcceptsBoundariesAndRejectsOversizedFields() {
        assertDoesNotThrow(() -> new ImageDescriptor("x".repeat(InputLimits.MAX_CONTENT_BYTES),
                4000, Collections.nCopies(8, "r".repeat(InputLimits.MAX_REF_BYTES)),
                "s".repeat(InputLimits.MAX_STYLE_BYTES)));
        assertThrows(IllegalArgumentException.class, () -> new ImageDescriptor(
                "漢".repeat(InputLimits.MAX_CONTENT_BYTES / 3 + 1), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ImageDescriptor(
                "x", 219, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ImageDescriptor(
                "x", null, List.of("r".repeat(InputLimits.MAX_REF_BYTES + 1)), null));
        assertThrows(IllegalArgumentException.class, () -> new ImageDescriptor(
                "x", null, null, "s".repeat(InputLimits.MAX_STYLE_BYTES + 1)));
    }

    @Test
    void formulaLimitCountsBytesAndPreservesBoundaryWhitespace() {
        assertDoesNotThrow(() -> InputLimits.formula(" " + "x".repeat(InputLimits.MAX_FORMULA_BYTES - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> InputLimits.formula("x".repeat(InputLimits.MAX_FORMULA_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> InputLimits.formula(" "));
    }
}
