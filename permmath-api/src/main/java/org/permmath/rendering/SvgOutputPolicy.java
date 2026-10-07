package org.permmath.rendering;

import static org.permmath.contract.ComplexityPolicy.Limit.*;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.stream.*;

import org.apache.commons.lang3.StringUtils;
import org.owasp.html.FilterUrlByProtocolAttributePolicy;
import org.permmath.contract.ComplexityPolicy;
import org.permmath.validation.InputLimits;

/** API-side output boundary. XML and CSS are parsed by StAX and ph-css, URLs by OWASP. */
public final class SvgOutputPolicy {
    static final String VERSION = "svg-output-policy-3";
    public static final String CSP = "sandbox; default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; "
            + "img-src 'none'; font-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; "
            + "base-uri 'none'; form-action 'none'";
    private static final String SVG = "http://www.w3.org/2000/svg";
    private static final String HTML = "http://www.w3.org/1999/xhtml";
    private static final String MATH = "http://www.w3.org/1998/Math/MathML";
    private static final String XLINK = "http://www.w3.org/1999/xlink";
    private static final Set<String> NAMESPACES = Set.of(SVG, HTML, MATH, XLINK, XMLConstants.XML_NS_URI);
    private static final Set<String> SVG_ELEMENTS = Set.of("svg", "g", "defs", "path", "use", "rect", "line",
            "circle", "ellipse", "polygon", "polyline", "text", "tspan", "title", "desc", "clipPath", "a",
            "foreignObject");
    private static final Set<String> HTML_ELEMENTS = Set.of("body", "div", "span", "p", "br", "hr", "h1", "h2",
            "h3", "h4", "h5", "h6", "b", "strong", "i", "em", "u", "s", "sub", "sup", "pre", "code",
            "blockquote", "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "a",
            "mjx-container", "mjx-assistive-mml");
    private static final Set<String> MATH_ELEMENTS = Set.of("math", "mrow", "mi", "mn", "mo", "mtext", "mspace",
            "ms", "mfrac", "msqrt", "mroot", "mstyle", "merror", "mpadded", "mphantom", "mfenced", "menclose",
            "msub", "msup", "msubsup", "munder", "mover", "munderover", "mmultiscripts", "mprescripts", "none",
            "mtable", "mtr", "mlabeledtr", "mtd", "semantics", "annotation");
    private static final Set<String> COMMON_ATTRIBUTES = Set.of("id", "class", "title", "style", "role", "dir");
    private static final Set<String> SVG_ATTRIBUTES = Set.of("width", "height", "viewBox", "preserveAspectRatio",
            "version", "focusable", "d", "x", "y", "x1", "x2", "y1", "y2", "cx", "cy", "r", "rx", "ry",
            "dx", "dy", "points", "transform", "fill", "fill-rule", "fill-opacity", "stroke", "stroke-width",
            "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-dasharray",
            "stroke-dashoffset", "clip-path", "clip-rule", "clipPathUnits", "text-anchor", "font-size",
            "font-family", "font-style", "font-weight", "color", "opacity", "display", "visibility", "pointer-events");
    private static final Set<String> HTML_ATTRIBUTES = Set.of("colspan", "rowspan", "jax", "display", "tabindex",
            "ctxtmenu_counter", "unselectable");
    private static final Set<String> MATH_ATTRIBUTES = Set.of("display", "displaystyle", "mathvariant", "mathsize",
            "mathcolor", "mathbackground", "scriptlevel", "scriptsizemultiplier", "scriptminsize", "stretchy",
            "symmetric", "fence", "separator", "form", "lspace", "rspace", "minsize", "maxsize", "largeop",
            "movablelimits", "accent", "accentunder", "align", "rowalign", "columnalign", "rowspacing",
            "columnspacing", "columnwidth", "rowlines", "columnlines", "frame", "framespacing", "equalrows",
            "equalcolumns", "side", "minlabelspacing", "linethickness", "bevelled", "width", "height", "depth",
            "voffset", "notation", "open", "close", "separators", "encoding");
    private static final Set<String> PAINT_ATTRIBUTES = Set.of("fill", "stroke", "clip-path", "color",
            "mathcolor", "mathbackground");
    private static final FilterUrlByProtocolAttributePolicy HTTPS =
            new FilterUrlByProtocolAttributePolicy(Set.of("https"));
    private static final ComplexityPolicy LIMITS = ComplexityPolicy.CURRENT;

    private SvgOutputPolicy() {
    }

    private static void checkDimensions(XMLStreamReader reader) throws IOException {
        double width = dimension(reader.getAttributeValue(null, "width"));
        double height = dimension(reader.getAttributeValue(null, "height"));
        if (width > LIMITS.get(RENDER_DIMENSION) || height > LIMITS.get(RENDER_DIMENSION)
                || width * height > LIMITS.get(RENDER_AREA)) throw rejected();
    }

    private static double dimension(String value) throws IOException {
        if (value == null) {
            return 0;
        }
        double factor = 1;
        if (value.endsWith("ex")) {
            factor = LIMITS.get(EX_PIXELS);
            value = value.substring(0, value.length() - 2);
        }
        else if (value.endsWith("em")) {
            factor = LIMITS.get(EM_PIXELS);
            value = value.substring(0, value.length() - 2);
        }
        else if (value.endsWith("px")) {
            value = value.substring(0, value.length() - 2);
        }
        try {
            double result = Double.parseDouble(value) * factor;
            if (!Double.isFinite(result) || result <= 0) {
                throw rejected();
            }
            return result;
        } catch (NumberFormatException invalid) {
            throw rejected();
        }
    }

    static String sanitize(String svg) throws IOException {
        if (!StringUtils.isNotBlank(svg) || svg.length() > LIMITS.get(SVG_BYTES)
                || InputLimits.utf8Length(svg) > LIMITS.get(SVG_BYTES)) {
            throw rejected();
        }
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((_, _, _, _) -> {
            throw new XMLStreamException("External XML resource denied");
        });
        XMLStreamReader reader = null;
        XMLStreamWriter writer = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(svg));
            var result = new StringWriter();
            writer = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(result);
            var stack = new ArrayDeque<String>();
            int count = 0;
            int foreignDepth = 0;
            boolean rootSeen = false;
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String ns = reader.getNamespaceURI();
                        String name = reader.getLocalName();
                        if (++count > LIMITS.get(RENDER_NODES) || stack.size() >= LIMITS.get(RENDER_DEPTH)
                                || reader.getAttributeCount() > LIMITS.get(ATTRIBUTES)
                                || reader.getNamespaceCount() > LIMITS.get(NAMESPACE_DECLARATIONS)) {
                            throw rejected();
                        }
                        if (stack.isEmpty()) {
                            if (rootSeen || !SVG.equals(ns) || !"svg".equals(name)) {
                                throw rejected();
                            }
                            rootSeen = true;
                            checkDimensions(reader);
                        }
                        if (SVG.equals(ns)) {
                            if (!SVG_ELEMENTS.contains(name)) {
                                throw rejected();
                            }
                            if ("foreignObject".equals(name)) {
                                if (stack.size() != 1 || foreignDepth != 0) {
                                    throw rejected();
                                }
                                foreignDepth = stack.size() + 1;
                            }
                        } else if (foreignDepth == 0 || !(HTML.equals(ns) && HTML_ELEMENTS.contains(name)
                                || MATH.equals(ns) && MATH_ELEMENTS.contains(name))) {
                            throw rejected();
                        }
                        stack.push(name);
                        writer.writeStartElement(reader.getPrefix(), name, ns);
                        for (int i = 0; i < reader.getNamespaceCount(); i++) {
                            if (!NAMESPACES.contains(reader.getNamespaceURI(i))) {
                                throw rejected();
                            }
                            String prefix = reader.getNamespacePrefix(i);
                            if (prefix == null) {
                                writer.writeDefaultNamespace(reader.getNamespaceURI(i));
                            } else {
                                writer.writeNamespace(prefix, reader.getNamespaceURI(i));
                            }
                        }
                        for (int i = 0; i < reader.getAttributeCount(); i++) {
                            writeAttribute(reader, writer, i, ns, name);
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        writer.writeEndElement();
                        if (stack.size() == foreignDepth) {
                            foreignDepth = 0;
                        }
                        stack.pop();
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            writer.writeCharacters(reader.getText());
                        } else if (!reader.isWhiteSpace()) {
                            throw rejected();
                        }
                    }
                    case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE,
                            XMLStreamConstants.PROCESSING_INSTRUCTION -> throw rejected();
                    default -> {
                        // Comments and the XML declaration are omitted.
                    }
                }
                if (result.getBuffer().length() > LIMITS.get(SVG_BYTES)) {
                    throw rejected();
                }
            }
            writer.flush();
            String clean = result.toString();
            if (!rootSeen || !stack.isEmpty() || InputLimits.utf8Length(clean) > LIMITS.get(SVG_BYTES)) {
                throw rejected();
            }
            return clean;
        } catch (XMLStreamException | IllegalArgumentException invalid) {
            // Parser/renderer diagnostics can contain the document. Do not propagate their text.
            throw rejected();
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (XMLStreamException ignored) {
                // Cleanup must not replace the validation result or rejection.
            }
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (XMLStreamException ignored) {
                // Cleanup must not replace the validation result or rejection.
            }
        }
    }

    private static void writeAttribute(XMLStreamReader reader, XMLStreamWriter writer, int index,
            String ns, String element) throws IOException, XMLStreamException {
        String attrNS = reader.getAttributeNamespace(index);
        String name = reader.getAttributeLocalName(index);
        String value = reader.getAttributeValue(index);
        if (attrNS == null) {
            attrNS = "";
        }
        if (XMLConstants.XML_NS_URI.equals(attrNS)) {
            if (!Set.of("space", "lang").contains(name)) {
                throw rejected();
            }
        } else if (XLINK.equals(attrNS)) {
            if (!SVG.equals(ns) || !"href".equals(name)) {
                throw rejected();
            }
        } else if (!attrNS.isEmpty()) {
            throw rejected();
        } else if ("href".equals(name)) {
            if (!("a".equals(element) || MATH.equals(ns) || SVG.equals(ns) && "use".equals(element))) {
                throw rejected();
            }
        } else if (!COMMON_ATTRIBUTES.contains(name) && !name.startsWith("aria-") && !name.startsWith("data-")
                && !(SVG.equals(ns) && SVG_ATTRIBUTES.contains(name))
                && !(HTML.equals(ns) && HTML_ATTRIBUTES.contains(name))
                && !(MATH.equals(ns) && MATH_ATTRIBUTES.contains(name))) {
            throw rejected();
        }

        if ("href".equals(name)) {
            value = link("a".equals(element) || MATH.equals(ns), value);
        } else if ("style".equals(name)) {
            if (value.length() > LIMITS.get(INLINE_STYLE_CHARS)) {
                throw rejected();
            }
            value = StylesheetPolicy.outputDeclarations(value);
        } else if (PAINT_ATTRIBUTES.contains(name)) {
            // ph-css handles CSS escapes/functions, including URI values hidden by escaping.
            String property = switch (name) {
                case "mathcolor" -> "color";
                case "mathbackground" -> "background-color";
                default -> name;
            };
            String declaration = StylesheetPolicy.outputDeclarations(property + ":" + value);
            if (declaration.isEmpty()) {
                throw rejected();
            }
            value = declaration.substring(declaration.indexOf(':') + 1).replaceFirst(";$", "");
        }
        if (attrNS.isEmpty()) {
            writer.writeAttribute(name, value);
        } else {
            writer.writeAttribute(reader.getAttributePrefix(index), attrNS, name, value);
        }
    }

    static boolean localReference(String value) {
        return value != null && value.matches("#[A-Za-z_][A-Za-z0-9_.:-]*");
    }

    private static String link(boolean hyperlink, String value) throws IOException {
        if (localReference(value)) {
            return value;
        }
        String clean = HTTPS.apply("a", "href", value);
        if (!hyperlink || clean == null) {
            throw rejected();
        }
        try {
            URI uri = URI.create(clean);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw rejected();
            }
        } catch (IllegalArgumentException invalid) {
            throw rejected();
        }
        return clean;
    }

    private static IOException rejected() {
        return new IOException("Renderer produced invalid SVG output");
    }
}
