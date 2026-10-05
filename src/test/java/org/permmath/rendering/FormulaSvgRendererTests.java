package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.junit.jupiter.api.Test;

class FormulaSvgRendererTests {
    @Test
    void rendersStandaloneSvgWithEmbeddedGlyphsAndDifferentFormulaSizes() throws Exception {
        try (var renderer = new FormulaSvgRenderer()) {
            var simple = parse(renderer.render("x^2"));
            var rendered = renderer.render("sum_(i=1)^n i^3=((n(n+1))/2)^2");
            var sum = parse(rendered);
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/formula.svg"), rendered);
            assertEquals("svg", sum.getDocumentElement().getLocalName());
            assertEquals("http://www.w3.org/2000/svg", sum.getDocumentElement().getNamespaceURI());
            assertTrue(sum.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").getLength() > 0);
            var uses = sum.getElementsByTagNameNS("http://www.w3.org/2000/svg", "use");
            assertTrue(uses.getLength() > 0);
            for (int i = 0; i < uses.getLength(); i++) {
                var element = (org.w3c.dom.Element) uses.item(i);
                assertTrue(element.getAttributeNS("http://www.w3.org/1999/xlink", "href").startsWith("#"),
                        "Glyph references must be local to the returned SVG");
            }
            assertNotEquals(simple.getDocumentElement().getAttribute("viewBox"),
                    sum.getDocumentElement().getAttribute("viewBox"));
        }
    }

    @Test
    void formulaIsPassedAsDataRatherThanExecutableJavaScript() throws Exception {
        try (var renderer = new FormulaSvgRenderer()) {
            assertEquals("svg", parse(renderer.render("\"; throw new Error('injected'); //"))
                    .getDocumentElement().getLocalName());
            assertEquals("svg", parse(renderer.render("a + b")).getDocumentElement().getLocalName());
        }
    }

    @Test
    void blankInputIsRejected() {
        try (var renderer = new FormulaSvgRenderer()) {
            assertThrows(IllegalArgumentException.class, () -> renderer.render(" "));
        }
    }

    @Test
    void sharedRendererHandlesConcurrentRequests() throws Exception {
        try (var renderer = new FormulaSvgRenderer();
             var requests = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (String formula : java.util.List.of("x^2", "sqrt(x)", "a+b", "sum_(i=1)^n i")) {
                results.add(requests.submit(() -> renderer.render(formula)));
            }
            for (var result : results) {
                assertEquals("svg", parse(result.get(20, java.util.concurrent.TimeUnit.SECONDS))
                        .getDocumentElement().getLocalName());
            }
        }
    }

    private static org.w3c.dom.Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }
}
