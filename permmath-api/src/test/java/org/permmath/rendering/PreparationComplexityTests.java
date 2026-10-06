package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.permmath.contract.ComplexityPolicy;

class PreparationComplexityTests {
    @Test
    void acceptsSubstantialDocumentsAndStyles() {
        String html = "<p>Some useful mathematics `sqrt(x^2)`</p>".repeat(1000);
        assertDoesNotThrow(() -> PreparationComplexity.html(html));
        assertDoesNotThrow(() -> PreparationComplexity.dom(Jsoup.parse(html).body()));
        assertDoesNotThrow(() -> StylesheetPolicy.stylesheet("/*" + "x".repeat(10000) + "*/ .a{color:red}"));
        assertDoesNotThrow(() -> StylesheetPolicy.stylesheet(".a{display:grid;grid-template-columns:repeat(1024,1px)}"));
        assertDoesNotThrow(() -> ComplexityPolicy.CURRENT.checkMathInput("x+".repeat(10000) + "x"));
    }
    @Test
    void rejectsHtmlStructureIncludingSelfClosingBypass() {
        assertThrows(IllegalArgumentException.class, () -> PreparationComplexity.html("<div>".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> PreparationComplexity.html("<div/>".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> PreparationComplexity.html("<p>x</p>".repeat(20001)));
        // Math expression discovery is owned by MathJax, rather than a second delimiter scanner.
        assertDoesNotThrow(() -> PreparationComplexity.dom(Jsoup.parse("<p>&#96;x&#96;</p>").body()));
    }
    @Test
    void rejectsCssBeforeParserAndHugeGridRepetition() {
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet("@media all{".repeat(33)));
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet(".x{width:" + "calc(".repeat(65) + "1px}"));
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet(".x{color:red}".repeat(20001)));
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet(".a,.b,.c{color:red}".repeat(7000)));
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet(".x{grid-template-columns:repeat(100000000,1px)}"));
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet(".x{grid-template-columns:repeat(+100000000,1px)}"));
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet("." + "x".repeat(4097) + "{color:red}"));
    }
    @Test
    void boundsMathInputWithoutReimplementingItsGrammar() {
        assertThrows(IllegalArgumentException.class, () -> ComplexityPolicy.CURRENT.checkMathInput("x".repeat(262145)));
        assertDoesNotThrow(() -> ComplexityPolicy.CURRENT.checkMathInput("\\text{parentheses ( and [ in text}"));
    }

    @Test
    void libraryLexersHandleCommentsStringsEscapesAndImpliedHtmlClosures() {
        assertDoesNotThrow(() -> PreparationComplexity.html("<ul>" + "<li>Item".repeat(1000) + "</ul>"));
        assertDoesNotThrow(() -> StylesheetPolicy.stylesheet(".a{content:'" + "({[".repeat(1000) + "';color:red}"));
        assertDoesNotThrow(() -> StylesheetPolicy.stylesheet("/*" + "({[".repeat(1000) + "*/ .a{color:red}"));
        assertThrows(IllegalArgumentException.class, () -> StylesheetPolicy.stylesheet(".a{width:" + "c\\61 lc(".repeat(65) + "1px}"));
    }
}
