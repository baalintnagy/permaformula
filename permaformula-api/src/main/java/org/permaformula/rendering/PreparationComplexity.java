package org.permaformula.rendering;

import static com.helger.css.parser.ParserCSS30Constants.COMMA;
import static com.helger.css.parser.ParserCSS30Constants.EOF;
import static com.helger.css.parser.ParserCSS30Constants.FUNCTION;
import static com.helger.css.parser.ParserCSS30Constants.FUNCTION_CALC;
import static com.helger.css.parser.ParserCSS30Constants.LBRACE;
import static com.helger.css.parser.ParserCSS30Constants.LROUND;
import static com.helger.css.parser.ParserCSS30Constants.LSQUARE;
import static com.helger.css.parser.ParserCSS30Constants.RBRACE;
import static com.helger.css.parser.ParserCSS30Constants.RROUND;
import static com.helger.css.parser.ParserCSS30Constants.RSQUARE;
import static com.helger.css.parser.ParserCSS30Constants.S;
import static com.helger.css.parser.ParserCSS30Constants.SEMICOLON;
import static com.helger.css.parser.ParserCSS30Constants.SINGLE_LINE_COMMENT;
import static com.helger.css.parser.ParserCSS30Constants.STRING1;
import static com.helger.css.parser.ParserCSS30Constants.STRING2;
import static org.permaformula.contract.ComplexityPolicy.Limit.ATTRIBUTES;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_BLOCK_DEPTH;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_BRACKET_DEPTH;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_DECLARATIONS;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_FUNCTION_DEPTH;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_RULES;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_TOKENS;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_TOKEN_CHARS;
import static org.permaformula.contract.ComplexityPolicy.Limit.HTML_DEPTH;
import static org.permaformula.contract.ComplexityPolicy.Limit.HTML_ELEMENTS;
import static org.permaformula.contract.ComplexityPolicy.Limit.HTML_NODES;
import static org.permaformula.contract.ComplexityPolicy.Limit.INLINE_STYLE_CHARS;
import static org.permaformula.validation.QuickValidator.validate;
import static org.permaformula.validation.QuickValidator.violation;

import java.io.StringReader;

import com.helger.css.parser.CSSCharStream;
import com.helger.css.parser.ParserCSS30TokenManager;
import com.helger.css.parser.TokenMgrException;
import com.helger.css.reader.errorhandler.DoNothingCSSParseErrorHandler;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;
import org.jsoup.parser.StreamParser;
import org.jsoup.select.NodeVisitor;
import org.permaformula.contract.ComplexityPolicy;

/** Resource checks around library parsing; no application HTML/CSS/math lexer. */
final class PreparationComplexity {
    private static final ComplexityPolicy LIMITS = ComplexityPolicy.CURRENT;

    private PreparationComplexity() {
    }

    static void html(String source) {
        // jsoup bounds its stack while streaming; abort before retaining excess elements.
        try (StreamParser stream = new StreamParser(
            Parser.htmlParser()
                .setMaxDepth(LIMITS.get(HTML_DEPTH) + 4))) {
            stream.parse(source, "");
            int elements = 0,
                nodes = 0;
            for (var iterator = stream.iterator(); iterator.hasNext();) {
                Element element = iterator.next();
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
                if (++nodes > LIMITS.get(HTML_NODES)
                    || depth > LIMITS.get(HTML_DEPTH)) {
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
        if (element.attributes()
            .size() > LIMITS.get(ATTRIBUTES)) {
            reject("HTML attributes");
        }
        if (element.attr("style")
            .length() > LIMITS.get(INLINE_STYLE_CHARS)) {
            reject("Inline style size");
        }
    }

    /**
     * ph-css has no parser budget setting; guard its real token stream before recursive parsing.
     */
    static int css(String source) {
        if (!StringUtils.isNotBlank(source)) {
            return 0;
        }
        CSSCharStream characters = new CSSCharStream(new StringReader(source));
        ParserCSS30TokenManager lexer = new ParserCSS30TokenManager(characters);
        lexer.setCustomErrorHandler(new DoNothingCSSParseErrorHandler());
        int tokens = 0,
            blocks = 0,
            functions = 0,
            brackets = 0,
            rules = 0,
            declarations = 0;
        int selectors = 0,
            commas = 0;
        try {
            for (var token = lexer.getNextToken(); token.kind != EOF; token = lexer
                .getNextToken()) {
                if (token.kind == S
                    || token.kind == SINGLE_LINE_COMMENT) {
                    continue;
                }
                if (++tokens > LIMITS.get(CSS_TOKENS)) {
                    reject("CSS tokens");
                }
                if (token.kind != STRING1
                    && token.kind != STRING2
                    && token.image.length() > LIMITS.get(CSS_TOKEN_CHARS)) {
                    reject("CSS token length");
                }
                // ph-css function tokens include their opening parenthesis, including escaped
                // names.
                if (token.kind == LROUND
                    || token.kind >= FUNCTION_CALC
                        && token.kind <= FUNCTION) {
                    if (++functions > LIMITS.get(CSS_FUNCTION_DEPTH)) {
                        reject("CSS function nesting");
                    }
                }
                switch (token.kind) {
                case LBRACE -> {
                    if (++blocks > LIMITS.get(CSS_BLOCK_DEPTH)
                        || ++rules > LIMITS.get(CSS_RULES)) {
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
                    if (functions == 0
                        && brackets == 0) {
                        commas++;
                    }
                }
                case SEMICOLON -> {
                    if (++declarations > LIMITS.get(CSS_DECLARATIONS)) {
                        reject("CSS declarations");
                    }
                    commas = 0;
                }
                default -> {
                }
                }
            }
            return selectors;
        } catch (TokenMgrException _) {
            throw violation("Invalid CSS stylesheet");
        } finally {
            characters.done();
        }
    }

    static void reject(String category) {
        validate(false,
            "%s exceeds complexity limits".formatted(category));
    }
}
