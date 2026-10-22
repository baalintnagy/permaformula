package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.ConstraintViolationException;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.permaformula.contract.ComplexityPolicy;

class PreparationComplexityTests {
    @Test
    void acceptsSubstantialDocumentsAndStyles() {
        String html = "<p>Some useful mathematics `sqrt(x^2)`</p>".repeat(1000);
        assertDoesNotThrow(() -> PreparationComplexity.html(html));
        assertDoesNotThrow(() -> PreparationComplexity.dom(Jsoup.parse(html)
            .body()));
        assertDoesNotThrow(
            () -> StylesheetPolicy.stylesheet("/*%s*/ .a{color:red}".formatted("x".repeat(10000))));
        assertDoesNotThrow(() -> StylesheetPolicy
            .stylesheet(".a{display:grid;grid-template-columns:repeat(1024,1px)}"));
        assertDoesNotThrow(() -> ComplexityPolicy.CURRENT.checkMathInput("%sx".formatted(
            "x+".repeat(10000))));
    }

    @Test
    void rejectsHtmlStructureIncludingSelfClosingBypass() {
        assertThrows(ConstraintViolationException.class,
            () -> PreparationComplexity.html("<div>".repeat(129)));
        assertThrows(ConstraintViolationException.class,
            () -> PreparationComplexity.html("<div/>".repeat(129)));
        assertThrows(ConstraintViolationException.class,
            () -> PreparationComplexity.html("<p>x</p>".repeat(20001)));
        // Math expression discovery is owned by MathJax, rather than a second delimiter scanner.
        assertDoesNotThrow(
            () -> PreparationComplexity.dom(Jsoup.parse("<p>&#96;x&#96;</p>")
                .body()));
    }

    @Test
    void rejectsCssBeforeParserAndHugeGridRepetition() {
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet("@media all{".repeat(33)));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".x{width:%s1px}".formatted("calc(".repeat(65))));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".x{color:red}".repeat(20001)));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".a,.b,.c{color:red}".repeat(7000)));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".x{grid-template-columns:repeat(100000000,1px)}"));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".x{grid-template-columns:repeat(+100000000,1px)}"));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".%s{color:red}".formatted("x".repeat(4097))));
    }

    @Test
    void boundsMathInputWithoutReimplementingItsGrammar() {
        int maximum = ComplexityPolicy.CURRENT.get(ComplexityPolicy.Limit.MATH_CHARS);
        assertDoesNotThrow(() -> ComplexityPolicy.CURRENT.checkMathInput("x".repeat(maximum)));
        assertThrows(ConstraintViolationException.class,
            () -> ComplexityPolicy.CURRENT.checkMathInput("x".repeat(maximum + 1)));
        assertDoesNotThrow(
            () -> ComplexityPolicy.CURRENT.checkMathInput("\\text{parentheses ( and [ in text}"));
    }

    @Test
    void libraryLexersHandleCommentsStringsEscapesAndImpliedHtmlClosures() {
        assertDoesNotThrow(
            () -> PreparationComplexity.html("<ul>%s</ul>".formatted("<li>Item".repeat(1000))));
        assertDoesNotThrow(() -> StylesheetPolicy
            .stylesheet(".a{content:'%s';color:red}".formatted("({[".repeat(1000))));
        assertDoesNotThrow(
            () -> StylesheetPolicy
                .stylesheet("/*%s*/ .a{color:red}".formatted("({[".repeat(1000))));
        assertThrows(ConstraintViolationException.class,
            () -> StylesheetPolicy.stylesheet(".a{width:%s1px}".formatted("c\\61 lc(".repeat(65))));
    }
}
