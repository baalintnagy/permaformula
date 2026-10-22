package org.permaformula.rendering;

import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_HTML_WORK;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_RULES;
import static org.permaformula.validation.QuickValidator.validate;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.url.ImageDescriptor;

/**
 * Presentation-only input: no submitted URLs, scripts or embedded documents.
 */
final class RenderContentPolicy {

    private static final Set<String> ELEMENTS = Set.of(
        "div", "span", "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
        "b", "strong", "i", "em", "u", "s", "sub", "sup", "pre", "code", "blockquote",
        "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "th", "td");
    private static final Set<String> ATTRIBUTES = Set.of("class", "title", "dir", "style");

    static void validateHtml(String source) {
        PreparationComplexity.html(source);
        Parser parser = Parser.htmlParser()
            .setTrackErrors(1);
        Document fragment = Document.createShell("");
        fragment.body()
            .appendChildren(parser.parseFragmentInput(source, new Element("body"), ""));
        validate(parser.getErrors()
            .isEmpty(),
            "Invalid HTML structure");
        fragment.body()
            .forEachNode(node -> {
                if (node == fragment.body()) {
                    return;
                }
                validate(node instanceof Element
                    || node instanceof TextNode
                    || node instanceof Comment, "Invalid HTML node");
                if (node instanceof Element element) {
                    validate(ELEMENTS.contains(element.normalName()),
                        "Unsupported HTML element");
                    element.attributes()
                        .forEach(attribute -> {
                            String name = attribute.getKey();
                            boolean span = Set.of("td", "th")
                                .contains(element.normalName())
                                && Set.of("colspan", "rowspan")
                                    .contains(name)
                                && attribute.getValue()
                                    .matches("[0-9]{1,3}");
                            validate(ATTRIBUTES.contains(name)
                                || span,
                                "Unsupported HTML attribute");
                            if ("style".equals(name)) {
                                StylesheetPolicy.declarations(attribute.getValue());
                            }
                        });
                }
            });
    }

    static String document(String template, ImageDescriptor descriptor) throws IOException {
        return document(template, descriptor,
            new StylesheetFetcher()
                .fetchAll(descriptor.styleRefs()));
    }

    static String document(String template, ImageDescriptor descriptor, List<String> externalCss) {
        PreparationComplexity.html(descriptor.forumla());
        int rules = PreparationComplexity.css(descriptor.style() == null ? ""
            : descriptor.style())
            + externalCss.stream()
                .mapToInt(PreparationComplexity::css)
                .sum();
        if (rules > ComplexityPolicy.CURRENT.get(CSS_RULES)) {
            PreparationComplexity.reject("Combined CSS rules/selectors");
        }
        Document document = Jsoup.parse(template);
        document.outputSettings()
            .prettyPrint(false);
        String nonce = UUID.randomUUID()
            .toString();
        document.select("script")
            .forEach(script -> script.attr("nonce", nonce));
        document.head()
            .prependElement("meta")
            .attr("http-equiv", "Content-Security-Policy")
            .attr("content",
                """
                default-src 'none'; script-src 'nonce-%s' 'strict-dynamic'; style-src \
                'unsafe-inline'; img-src 'none'; connect-src 'none'; frame-src 'none'; \
                worker-src \
                'none'; object-src 'none'; base-uri 'none'; form-action 'none'\
                """
                    .formatted(nonce));
        // DataNode preserves the stylesheet's text boundary; never substitute user text into a
        // template.
        externalCss.forEach(
            sheet -> document.head()
                .appendElement("style")
                .appendChild(new DataNode(StylesheetPolicy.externalStylesheet(sheet))));
        document.head()
            .appendElement("style")
            .appendChild(new DataNode(css(descriptor.style())));
        validateHtml(descriptor.forumla());
        document.body()
            .html(descriptor.forumla());
        PreparationComplexity.dom(document.body());
        if ((long) rules * document.body()
            .getAllElements()
            .size() > ComplexityPolicy.CURRENT.get(CSS_HTML_WORK)) {
            PreparationComplexity.reject("Combined CSS/HTML work");
        }
        return document.outerHtml();
    }

    static String css(String source) {
        return StylesheetPolicy.stylesheet(source);
    }
}
