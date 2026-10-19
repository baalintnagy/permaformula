package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permaformula.contract.ComplexityPolicy;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

class AsciiMathRendererTests {
    @Test
    void configuredPolicyReachesGraalMathJaxAndRejectsExpandedTrees() throws Exception {
        var policy = ComplexityPolicy.load(new Properties(),
            Map.of("PERMAFORMULA_COMPLEXITY_MATH_NODES", "20"));
        try (var renderer = new AsciiMathRenderer(policy)) {
            assertThrows(RuntimeException.class, () -> renderer.render("x+".repeat(30) + "x"));
            assertEquals("svg", parse(renderer.render("x")).getDocumentElement().getLocalName());
        }
    }

    @Test
    void rendersStandaloneSvgWithEmbeddedGlyphsAndDifferentFormulaSizes() throws Exception {
        try (var renderer = new AsciiMathRenderer()) {
            var simple = parse(renderer.render("x^2"));
            var rendered = renderer.render("sum_(i=1)^n i^3=((n(n+1))/2)^2");
            var sum = parse(rendered);
            Files.writeString(Path.of("target/formula.svg"), rendered);
            assertEquals("svg", sum.getDocumentElement().getLocalName());
            assertEquals("http://www.w3.org/2000/svg", sum.getDocumentElement().getNamespaceURI());
            assertTrue(
                sum.getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").getLength() > 0);
            var uses = sum.getElementsByTagNameNS("http://www.w3.org/2000/svg", "use");
            assertTrue(uses.getLength() > 0);
            assertAll(IntStream.range(0, uses.getLength())
                .mapToObj(i -> (Element) uses.item(i))
                .<Executable>map(element -> () -> assertTrue(
                    element.getAttributeNS("http://www.w3.org/1999/xlink", "href").startsWith("#"),
                    "Glyph references must be local to the returned SVG")));
            assertNotEquals(simple.getDocumentElement().getAttribute("viewBox"),
                sum.getDocumentElement().getAttribute("viewBox"));
        }
    }

    @Test
    void formulaIsPassedAsDataRatherThanExecutableJavaScript() throws Exception {
        try (var renderer = new AsciiMathRenderer()) {
            assertEquals("svg", parse(renderer.render("\"; throw new Error('injected'); //"))
                .getDocumentElement().getLocalName());
            assertEquals("svg",
                parse(renderer.render("a + b")).getDocumentElement().getLocalName());
        }
    }

    @Test
    void sharedRendererHandlesConcurrentRequests() throws Exception {
        try (var renderer = new AsciiMathRenderer();
            var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = List.of("x^2", "sqrt(x)", "a+b", "sum_(i=1)^n i").stream()
                .map(formula -> requests.submit(() -> renderer.render(formula)))
                .toList();
            assertAll(results.stream()
                .<Executable>map(
                    result -> () -> assertEquals("svg", parse(result.get(20, TimeUnit.SECONDS))
                        .getDocumentElement().getLocalName())));
        }
    }

    private static Document parse(String svg) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }
}
