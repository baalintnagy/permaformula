package org.permmath.rendering;

import static com.helger.css.parser.ParserCSS30Constants.*;
import static org.permmath.contract.ComplexityPolicy.Limit.*;

import java.io.StringReader;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.jsoup.parser.StreamParser;
import org.jsoup.select.NodeVisitor;
import org.permmath.contract.ComplexityPolicy;

import com.helger.css.parser.CSSCharStream;
import com.helger.css.parser.ParserCSS30TokenManager;
import com.helger.css.parser.TokenMgrException;
import com.helger.css.reader.errorhandler.DoNothingCSSParseErrorHandler;

/** Resource checks around library parsing; no application HTML/CSS/math lexer. */
final class PreparationComplexity {
    private static final ComplexityPolicy LIMITS = ComplexityPolicy.CURRENT;
    private PreparationComplexity() { }

    static void html(String source) {
        // jsoup bounds its stack while streaming; abort before retaining excess elements.
        try (var stream = new StreamParser(Parser.htmlParser().setMaxDepth(LIMITS.get(HTML_DEPTH) + 4))) {
            stream.parse(source, "");
            int elements = 0, nodes = 0;
            for (var iterator = stream.iterator(); iterator.hasNext();) {
                var element = iterator.next();
                if (++elements > LIMITS.get(HTML_ELEMENTS) + 3) {
                    reject("HTML elements");
                }
                nodes += element.childNodeSize();
                if (nodes > LIMITS.get(HTML_NODES)) {
                    reject("HTML nodes");
                }
                int depth = 0;
                for (var parent = element.parent(); parent != null; parent = parent.parent()) {
                    depth++;
                }
                if (depth > LIMITS.get(HTML_DEPTH) + 2) {
                    reject("HTML nesting");
                }
                checkElement(element);
            }
        }
    }

    static void dom(Node root) {
        root.traverse(new NodeVisitor() {
            int nodes, elements;
            @Override
            public void head(Node node, int depth) {
                if (++nodes > LIMITS.get(HTML_NODES) || depth > LIMITS.get(HTML_DEPTH)) {
                    reject("HTML DOM");
                }
                if (node instanceof Element element) {
                    if (++elements > LIMITS.get(HTML_ELEMENTS)) {
                        reject("HTML elements");
                    }
                    checkElement(element);
                }
            }
        });
    }

    private static void checkElement(Element element) {
        if (element.attributes().size() > LIMITS.get(ATTRIBUTES)) {
            reject("HTML attributes");
        }
        if (element.attr("style").length() > LIMITS.get(INLINE_STYLE_CHARS)) {
            reject("Inline style size");
        }
    }

    /** ph-css has no parser budget setting; guard its real token stream before recursive parsing. */
    static int css(String source) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        var characters = new CSSCharStream(new StringReader(source));
        var lexer = new ParserCSS30TokenManager(characters);
        lexer.setCustomErrorHandler(new DoNothingCSSParseErrorHandler());
        int tokens = 0, blocks = 0, functions = 0, brackets = 0, rules = 0, declarations = 0;
        int selectors = 0, commas = 0;
        try {
            for (var token = lexer.getNextToken(); token.kind != EOF; token = lexer.getNextToken()) {
                if (token.kind == S || token.kind == SINGLE_LINE_COMMENT) {
                    continue;
                }
                if (++tokens > LIMITS.get(CSS_TOKENS)) {
                    reject("CSS tokens");
                }
                if (token.kind != STRING1 && token.kind != STRING2 && token.image.length() > LIMITS.get(CSS_TOKEN_CHARS)) {
                    reject("CSS token length");
                }
                // ph-css function tokens include their opening parenthesis, including escaped names.
                if (token.kind == LROUND || token.kind >= FUNCTION_CALC && token.kind <= FUNCTION) {
                    if (++functions > LIMITS.get(CSS_FUNCTION_DEPTH)) {
                        reject("CSS function nesting");
                    }
                }
                switch (token.kind) {
                    case LBRACE -> {
                        if (++blocks > LIMITS.get(CSS_BLOCK_DEPTH) || ++rules > LIMITS.get(CSS_RULES)) {
                            reject("CSS rules/nesting");
                        }
                        selectors += commas + 1;
                        if (selectors > LIMITS.get(CSS_RULES)) {
                            reject("CSS selectors");
                        }
                        commas = 0;
                    }
                    case RBRACE -> {
                        blocks = Math.max(0, blocks - 1);
                        commas = 0;
                    }
                    case RROUND -> functions = Math.max(0, functions - 1);
                    case LSQUARE -> {
                        if (++brackets > LIMITS.get(CSS_BRACKET_DEPTH)) {
                            reject("CSS selector nesting");
                        }
                    }
                    case RSQUARE -> brackets = Math.max(0, brackets - 1);
                    case COMMA -> {
                        if (functions == 0 && brackets == 0) {
                            commas++;
                        }
                    }
                    case SEMICOLON -> {
                        if (++declarations > LIMITS.get(CSS_DECLARATIONS)) {
                            reject("CSS declarations");
                        }
                        commas = 0;
                    }
                    default -> { }
                }
            }
            return selectors;
        } catch (TokenMgrException invalid) {
            throw new IllegalArgumentException("Invalid CSS stylesheet");
        }
        finally {
            characters.done();
        }
    }

    static void reject(String category) {
        throw new IllegalArgumentException(category + " exceeds complexity limits");
    }
}
