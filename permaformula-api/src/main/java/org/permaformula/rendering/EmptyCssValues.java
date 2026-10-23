package org.permaformula.rendering;

import static com.helger.css.parser.ParserCSS30Constants.COLON;
import static com.helger.css.parser.ParserCSS30Constants.EOF;
import static com.helger.css.parser.ParserCSS30Constants.IDENT;
import static com.helger.css.parser.ParserCSS30Constants.IMPORTANT_SYM;
import static com.helger.css.parser.ParserCSS30Constants.LBRACE;
import static com.helger.css.parser.ParserCSS30Constants.RBRACE;
import static com.helger.css.parser.ParserCSS30Constants.S;
import static com.helger.css.parser.ParserCSS30Constants.SEMICOLON;
import static com.helger.css.parser.ParserCSS30Constants.SINGLE_LINE_COMMENT;

import java.io.StringReader;
import java.util.UUID;

import com.helger.css.decl.CSSDeclaration;
import com.helger.css.decl.CSSExpression;
import com.helger.css.decl.CSSExpressionMemberTermSimple;
import com.helger.css.parser.CSSCharStream;
import com.helger.css.parser.ParserCSS30TokenManager;
import com.helger.css.parser.Token;
import com.helger.css.reader.errorhandler.DoNothingCSSParseErrorHandler;

/** Bridge ph-css's missing empty custom-property grammar using its own lexer. */
record EmptyCssValues(String source, String marker) {
    static EmptyCssValues prepare(String source) {
        String marker = "pf-empty-%s".formatted(UUID.randomUUID());
        StringBuilder result = new StringBuilder();
        CSSCharStream characters = new CSSCharStream(new StringReader(source));
        characters.setTabSize(1);
        ParserCSS30TokenManager lexer = new ParserCSS30TokenManager(characters);
        lexer.setCustomErrorHandler(new DoNothingCSSParseErrorHandler());
        int previous = LBRACE;
        int copied = 0;
        int line = 1;
        int lineStart = 0;
        boolean property = false;
        boolean emptyValue = false;
        try {
            while (true) {
                Token token = lexer.getNextToken();
                if (token.kind == S
                    || token.kind == SINGLE_LINE_COMMENT) {
                    continue;
                }
                if (emptyValue
                    && (token.kind == SEMICOLON
                        || token.kind == RBRACE
                        || token.kind == IMPORTANT_SYM
                        || token.kind == EOF)) {
                    while (line < token.beginLine) {
                        char character = source.charAt(lineStart++);
                        if (character == '\r') {
                            if (lineStart < source.length()
                                && source.charAt(lineStart) == '\n') {
                                lineStart++;
                            }
                            line++;
                        } else if (character == '\n') {
                            line++;
                        }
                    }
                    int offset = token.kind == EOF ? source.length()
                        : lineStart + token.beginColumn - 1;
                    result.append(source, copied, offset)
                        .append(marker);
                    copied = offset;
                }
                emptyValue = token.kind == COLON
                    && property;
                property = token.kind == IDENT
                    && token.image.startsWith("--")
                    && (previous == LBRACE
                        || previous == SEMICOLON);
                previous = token.kind;
                if (token.kind == EOF) {
                    break;
                }
            }
        } finally {
            characters.done();
        }
        result.append(source, copied, source.length());
        return new EmptyCssValues(result.toString(), marker);
    }

    void restore(CSSDeclaration declaration) {
        CSSExpression expression = declaration.getExpression();
        if (declaration.getProperty()
            .startsWith("--")
            && expression.getMemberCount() == 1
            && expression.getMemberAtIndex(0) instanceof CSSExpressionMemberTermSimple term
            && marker.equals(term.getValue())) {
            // A whitespace value is distinct from initial: var() must not take its fallback.
            declaration.setExpression(CSSExpression.createSimple(" "));
        }
    }
}
