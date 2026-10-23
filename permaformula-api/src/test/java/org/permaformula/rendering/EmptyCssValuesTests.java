package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class EmptyCssValuesTests {
    @Test
    void preservesEmptyCustomValuesAndNeighboringDeclarations() {
        String source = ".x{--empty: ;color:red;--last:!important}";
        String result = StylesheetPolicy.externalStylesheet(source);
        assertEquals(".x{--empty: ;color:red;--last:  !important}", result);
        assertEquals(result, StylesheetPolicy.externalStylesheet(result));
        assertTrue(StylesheetPolicy.stylesheet(source)
            .contains("--empty: ;color:red"));
        assertEquals("--empty: ;color:red", StylesheetPolicy.declarations("--empty: ;color:red"));
    }

    @Test
    void usesLibraryTokenBoundariesAcrossCommentsStringsAndLineEndings() {
        for (String newline : new String[] { "\n", "\r\n", "\r" }) {
            String source = ".x{%s\t--empty:/**/%s ;content:'--literal: ;';color:red}"
                .formatted(newline, newline);
            String result = StylesheetPolicy.externalStylesheet(source);
            assertTrue(result.contains("--empty: ;"));
            assertTrue(result.contains("--literal: ;"));
            assertTrue(result.contains("color:red"));
        }
    }

    @Test
    void emptyValuesDoNotHideInvalidSyntaxOrResourceLimits() {
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.externalStylesheet(".x{--empty: ;width:calc(}"));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.externalStylesheet(
                ".x{--empty: ;grid-template-columns:repeat(999999999,1fr)}"));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".x{--empty: ;background:url(https://example.org)}"));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".x{color: ;}"));
    }
}
