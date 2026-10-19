package org.permaformula.url;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.List;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.permaformula.TestServices;
import org.permaformula.validation.InputLimits;
import org.permaformula.validation.ValidDescriptor;
import org.permaformula.validation.ValidFormula;
import org.permaformula.validation.ValidationText;

class InputLimitsTests {
    private static ImageDescriptor checkedDescriptor(String content, Integer width,
        List<String> refs, String style) {
        var descriptor = new ImageDescriptor(content, width, refs, style);
        TestServices.validated(new Boundary()).accept(descriptor);
        return descriptor;
    }

    public static class Boundary {
        public void accept(@ValidDescriptor ImageDescriptor descriptor) {
        }
    }

    private static void checkedFormula(String value) {
        TestServices.validated(new FormulaBoundary()).accept(value);
    }

    public static class FormulaBoundary {
        public void accept(@ValidFormula String value) {
        }
    }

    @Test
    void utf8BoundsPreserveSupplementaryCharactersAndMalformedSurrogateReplacement() {
        assertDoesNotThrow(() -> ValidationText.text("\uD83D\uDE00", 4, "Text"));
        assertThrows(ConstraintViolationException.class,
            () -> ValidationText.text("\uD83D\uDE00", 3, "Text"));
        assertDoesNotThrow(() -> ValidationText.text("\uD800x\uDC00", 3, "Text"));
        assertThrows(ConstraintViolationException.class,
            () -> ValidationText.text("\uD800x\uDC00", 2, "Text"));
    }

    @Test
    void descriptorAcceptsBoundariesAndRejectsOversizedFields() {
        assertDoesNotThrow(() -> checkedDescriptor("x".repeat(InputLimits.MAX_CONTENT_BYTES),
            4000, Collections.nCopies(8, "https://example.com/"
                + "r".repeat(InputLimits.MAX_REF_BYTES - "https://example.com/".length())),
            " ".repeat(InputLimits.MAX_STYLE_BYTES)));
        assertThrows(ConstraintViolationException.class, () -> checkedDescriptor(
            "漢".repeat(InputLimits.MAX_CONTENT_BYTES / 3 + 1), null, null, null));
        assertThrows(ConstraintViolationException.class, () -> checkedDescriptor(
            "x", 219, null, null));
        assertThrows(ConstraintViolationException.class, () -> checkedDescriptor(
            "x", null, List.of("r".repeat(InputLimits.MAX_REF_BYTES + 1)), null));
        assertThrows(ConstraintViolationException.class, () -> checkedDescriptor(
            "x", null, null, "s".repeat(InputLimits.MAX_STYLE_BYTES + 1)));
    }

    @Test
    void formulaLimitCountsBytesAndPreservesBoundaryWhitespace() {
        assertDoesNotThrow(
            () -> checkedFormula(" " + "x".repeat(InputLimits.MAX_FORMULA_BYTES - 1)));
        assertThrows(ConstraintViolationException.class,
            () -> checkedFormula("x".repeat(InputLimits.MAX_FORMULA_BYTES + 1)));
        assertThrows(ConstraintViolationException.class, () -> checkedFormula(" "));
    }
}
