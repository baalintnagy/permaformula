package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SvgOutputPolicyTests {
    private static final String OPEN =
        "<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink'>";
    @TempDir
    Path temp;

    @Test
    void preservesStyledForeignObjectMathAndGlyphReferences() throws Exception {
        String svg =
            """
            %s<defs><path id='glyph' d='M0 0L1 1'/></defs><foreignObject width='100%%' height=\
            '100%%'><div xmlns='http://www.w3.org/1999/xhtml' style='color:rgb(220,53,69);display:\
            flex;background:linear-gradient(red,blue);clip:rect(1px,1px,1px,1px)'><mjx-container \
            jax='SVG'><svg xmlns='http://www.w3.org/2000/svg'><use xlink:href='#glyph' fill=\
            'currentColor' transform='translate(12 3)'/></svg></mjx-container><mjx-assistive-mml>\
            <math xmlns='http://www.w3.org/1998/Math/MathML'><msup><mi>x</mi><mn>2</mn></msup></\
            math></mjx-assistive-mml></div></foreignObject></svg>\
            """
            .formatted(
                OPEN);
        String clean = SvgOutputPolicy.sanitize(svg);
        assertTrue(clean.contains("foreignObject"));
        assertTrue(clean.contains("xlink:href=\"#glyph\""));
        assertTrue(clean.contains("transform=\"translate(12 3)\""));
        assertTrue(clean.contains("display:flex"));
        assertTrue(clean.contains("linear-gradient"));
        assertTrue(clean.contains("clip:rect(1px,1px,1px,1px)"));
        assertTrue(clean.contains("<msup>"));
        assertEquals(clean, SvgOutputPolicy.sanitize(clean));
    }

    @ParameterizedTest
    @ValueSource(
        strings = { "javascript:alert(1)", "JaVaScRiPt:alert(1)", "java&#xA;script:alert(1)",
                "data:image/svg+xml,evil", "file:///etc/passwd", "//evil.example/", "/redirect",
                "http://example.com/" })
    void rejectsUnsafeOrRelativeLinks(String url) {
        assertThrows(IOException.class,
            () -> SvgOutputPolicy
                .sanitize("%s<a href='%s'><text>x</text></a></svg>".formatted(OPEN, url)));
    }

    @Test
    void keepsHttpsHyperlinksButNeverExternalUseReferences() throws Exception {
        assertTrue(SvgOutputPolicy.sanitize(
            "%s<a xlink:href='https://example.com/math'><text>x</text></a></svg>".formatted(
                OPEN))
            .contains("https://example.com/math"));
        assertThrows(IOException.class,
            () -> SvgOutputPolicy
                .sanitize("%s<use href='https://example.com/image.svg#x'/></svg>".formatted(OPEN)));
    }

    @ParameterizedTest
    @ValueSource(strings = { "<script>alert(1)</script>",
            "<animate attributeName='href' values='javascript:alert(1)'/>",
            "<set attributeName='href' to='javascript:alert(1)'/>",
            "<image href='https://example.com/x'/>",
            "<g onload='alert(1)'/>", "<g xml:base='https://example.com/'><use href='#x'/></g>",
            """
            <foreignObject><iframe xmlns='http://www.w3.org/1999/xhtml' \
            src='https://example.com/'/></foreignObject>\
            """
            ,
            """
            <foreignObject><div xmlns='http://www.w3.org/1999/xhtml'><svg \
            xmlns='http://www.w3.org/2000/svg'><foreignObject/></svg></div></foreignObject>\
            """
            ,
            """
            <foreignObject><annotation-xml xmlns='http://www.w3.org/1998/Math/MathML' \
            encoding='text/html'/></foreignObject>\
            """
            ,
            "<evil:g xmlns:evil='https://evil.example/'/>" })
    void rejectsActiveElementsAttributesAndNamespaceConfusion(String body) {
        assertThrows(IOException.class, () -> SvgOutputPolicy.sanitize("%s%s</svg>".formatted(
            OPEN,
            body)));
    }

    @Test
    void filtersCssUsingParserIncludingEscapesAndNestedUrls() throws Exception {
        String clean = SvgOutputPolicy.sanitize(
            """
            %s<g style="color:red;fill:url(#glyph);background-image:url(https://evil.example/\
            a);--bad:url(https://evil.example/b);color:expression(alert(1))"/></svg>\
            """
                .formatted(
                    OPEN));
        assertTrue(clean.contains("color:red"));
        assertTrue(clean.contains("url(#glyph)"));
        assertFalse(clean.contains("evil.example"));
        assertFalse(clean.contains("expression("));
        assertThrows(IOException.class,
            () -> SvgOutputPolicy
                .sanitize("%s<path fill='url(https://evil.example/x)'/></svg>".formatted(OPEN)));
        assertFalse(SvgOutputPolicy.sanitize(
            "%s<g style='background-image:u\\72l(https://evil.example/x)'/></svg>".formatted(
                OPEN))
            .contains("evil.example"));
        assertFalse(SvgOutputPolicy.sanitize(
            "%s<g style='width:calc(1px + url(https://evil.example/x))'/></svg>".formatted(
                OPEN))
            .contains("evil.example"));
    }

    @Test
    void rejectsDtdExternalEntitiesAndStylesheetInstructions() throws Exception {
        var secret = temp.resolve("secret.txt");
        Files.writeString(secret, "private-content");
        assertThrows(IOException.class,
            () -> SvgOutputPolicy.sanitize(
                "<!DOCTYPE svg [<!ENTITY xxe SYSTEM '%s'>]>%s<text>&xxe;</text></svg>".formatted(
                    secret.toUri(),
                    OPEN)));
        assertThrows(IOException.class,
            () -> SvgOutputPolicy.sanitize(
                "<?xml-stylesheet href='https://evil.example/x'?>%s</svg>".formatted(OPEN)));
        assertThrows(IOException.class,
            () -> SvgOutputPolicy.sanitize("<!DOCTYPE svg [<!ENTITY x 'x'>]>%s</svg>".formatted(
                OPEN)));
    }

    @Test
    void rejectsMalformedWrongRootAndExcessiveNesting() {
        assertThrows(IOException.class, () -> SvgOutputPolicy.sanitize("<svg/>"));
        assertThrows(IOException.class, () -> SvgOutputPolicy.sanitize("%s</g>".formatted(OPEN)));
        assertThrows(IOException.class,
            () -> SvgOutputPolicy.sanitize("%s%s%s</svg>".formatted(OPEN, "<g>".repeat(256),
                "</g>".repeat(256))));
        assertThrows(IOException.class,
            () -> SvgOutputPolicy.sanitize("%s%s</svg>".formatted(OPEN, "<g/>".repeat(100_000))));
        assertThrows(IOException.class,
            () -> SvgOutputPolicy.sanitize("%s<g style='%s'/></svg>".formatted(
                OPEN,
                " ".repeat(32_769))));
    }

    @Test
    void escapesCdataAsTextInsteadOfReemittingMarkup() throws Exception {
        String clean = SvgOutputPolicy.sanitize(
            "%s<text><![CDATA[<script>alert(1)</script>]]></text></svg>".formatted(OPEN));
        assertFalse(clean.contains("<script>"));
        assertTrue(clean.contains("&lt;script&gt;"));
    }

    @Test
    void independentlyRejectsExtremeViewportDimensions() throws Exception {
        assertThrows(IOException.class, () -> SvgOutputPolicy
            .sanitize("<svg xmlns='http://www.w3.org/2000/svg' width='4000' height='32768'/>"));
        assertThrows(IOException.class, () -> SvgOutputPolicy
            .sanitize("<svg xmlns='http://www.w3.org/2000/svg' width='1' height='Infinity'/>"));
        assertDoesNotThrow(() -> SvgOutputPolicy
            .sanitize("<svg xmlns='http://www.w3.org/2000/svg' width='1000' height='30000'/>"));
    }
}
