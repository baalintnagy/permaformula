package org.permmath.rendering;

import java.io.IOException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.permmath.url.ImageDescriptor;

/**
 * Presentation-only input: no submitted URLs, scripts or embedded documents.
 */
final class RenderContentPolicy {

    private static final PolicyFactory HTML = new HtmlPolicyBuilder()
            .allowElements("div", "span", "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
                    "b", "strong", "i", "em", "u", "s", "sub", "sup", "pre", "code", "blockquote",
                    "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "th", "td")
            .allowAttributes("class", "title", "dir").globally()
            .allowAttributes("colspan", "rowspan").matching(java.util.regex.Pattern.compile("[0-9]{1,3}"))
                    .onElements("td", "th")
            .allowWithoutAttributes("div", "span")
            .allowAttributes("style").matching((element, attribute, value) -> StylesheetPolicy.declarations(value))
                    .globally().toFactory();

    static String document(String template, ImageDescriptor descriptor) throws IOException {
        return document(template, descriptor, new StylesheetFetcher().fetchAll(descriptor.styleRefs()));
    }


    static String document(String template, ImageDescriptor descriptor, java.util.List<String> externalCss) throws IOException {
        var document = Jsoup.parse(template);
        document.outputSettings().prettyPrint(false);
        var nonce = java.util.UUID.randomUUID().toString();
        document.select("script").forEach(script -> script.attr("nonce", nonce));
        document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
                .attr("content", "default-src 'none'; script-src 'nonce-" + nonce
                        + "' 'strict-dynamic'; style-src 'unsafe-inline'; img-src 'none'; "
                        + "connect-src 'none'; frame-src 'none'; worker-src 'none'; "
                        + "object-src 'none'; base-uri 'none'; form-action 'none'");
        // DataNode preserves the stylesheet's text boundary; never substitute user text into a template.
        externalCss.forEach(sheet -> document.head().appendElement("style").appendChild(new DataNode(css(sheet))));
        document.head().appendElement("style").appendChild(new DataNode(css(descriptor.style())));
        document.body().html(HTML.sanitize(descriptor.forumla()));
        return document.outerHtml();
    }

    static String css(String source) {
        return StylesheetPolicy.stylesheet(source);
    }
}
