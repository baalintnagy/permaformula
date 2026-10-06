package org.permmath.rendering;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;
import com.helger.css.decl.*;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderDeclarationList;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.DoNothingCSSParseErrorHandler;
import com.helger.css.reader.errorhandler.DoNothingCSSInterpretErrorHandler;
import com.helger.css.handler.DoNothingCSSParseExceptionCallback;
import com.helger.css.writer.CSSWriter;
import com.helger.css.writer.CSSWriterSettings;
import org.owasp.html.CssSchema;

/**
 * Parsed presentation CSS, including variables, responsive rules and modern layout.
 */
final class StylesheetPolicy {

    private static final Set<String> MODERN_PROPERTIES = Set.of(
            "display", "position", "inset", "top", "right", "bottom", "left", "z-index",
            "box-sizing", "gap", "row-gap", "column-gap", "flex", "flex-basis", "flex-grow", "flex-shrink",
            "flex-direction", "flex-wrap", "align-items", "align-self", "align-content", "justify-content",
            "justify-items", "justify-self", "order", "grid", "grid-template", "grid-template-columns",
            "grid-template-rows", "grid-template-areas", "grid-auto-columns", "grid-auto-rows", "grid-auto-flow",
            "grid-column", "grid-row", "grid-area", "object-fit", "object-position", "aspect-ratio",
            "opacity", "transform", "transform-origin", "box-shadow", "text-shadow", "border-radius",
            "overflow", "overflow-x", "overflow-y", "text-overflow", "overflow-wrap", "word-break",
            "background", "background-image", "background-size", "background-position", "background-repeat",
            "content", "color-scheme", "vertical-align", "-webkit-font-smoothing", "-moz-osx-font-smoothing");

    private static final Set<String> FUNCTIONS = Set.of("var", "calc", "min", "max", "clamp", "env",
            "rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color", "color-mix",
            "linear-gradient", "radial-gradient", "conic-gradient", "repeating-linear-gradient",
            "repeating-radial-gradient", "repeating-conic-gradient", "translate", "translatex", "translatey",
            "translatez", "translate3d", "scale", "scalex", "scaley", "scalez", "scale3d", "rotate", "rotatex",
            "rotatey", "rotatez", "rotate3d", "skew", "skewx", "skewy", "matrix", "matrix3d", "perspective",
            "repeat", "minmax", "fit-content");

    private static final CSSWriterSettings WRITER = new CSSWriterSettings(true);

    private static final Set<String> SVG_PROPERTIES = Set.of("fill", "fill-rule", "fill-opacity", "stroke",
            "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit",
            "stroke-dasharray", "stroke-dashoffset", "clip-path", "clip-rule", "text-anchor", "dominant-baseline",
            "alignment-baseline", "baseline-shift", "vector-effect", "paint-order", "shape-rendering", "clip");

    /** Preserve vector paint and local glyph/clip references in exported presentation styles. */
    static String outputDeclarations(String source) {
        if (!StringUtils.isNotBlank(source)) {
            return "";
        }
        // The stylesheet parser supports empty custom properties emitted by getComputedStyle;
        // the standalone declaration-list parser does not. Never emit the wrapper or extra rules.
        var sheet = parse(".output{" + source + "}");
        if (sheet.getRuleCount() != 1 || sheet.getImportRuleCount() != 0 || sheet.getNamespaceRuleCount() != 0
                || !(sheet.getRuleAtIndex(0) instanceof CSSStyleRule declarations) || declarations.getRuleCount() != 0) {
            throw new IllegalArgumentException("Invalid SVG presentation style");
        }
        cleanDeclarations(declarations, true);
        var result = new CSSDeclarationList();
        declarations.getAllDeclarations().forEach(result::addDeclaration);
        return escapeBoundary(result.getAsCSSString(WRITER, 0));
    }

    static String stylesheet(String source) {
        if (!StringUtils.isNotBlank(source)) {
            return "";
        }
        var sheet = parse(source);
        // Imports must be expanded by the controlled fetcher, never the browser.
        sheet.removeAllImportRules();
        sheet.removeAllNamespaceRules();
        cleanRules(sheet, 0);
        return escapeBoundary(new CSSWriter(WRITER).getCSSAsString(sheet));
    }

    static String declarations(String source) {
        var declarations = CSSReaderDeclarationList.readFromString(source, readerSettings());
        if (declarations == null) {
            return "";
        }
        cleanDeclarations(declarations);
        return escapeBoundary(declarations.getAsCSSString(WRITER, 0));
    }

    static CascadingStyleSheet parse(String source) {
        var sheet = CSSReader.readFromStringReader(source, readerSettings());
        if (sheet == null) {
            throw new IllegalArgumentException("Invalid CSS stylesheet");
        }
        return sheet;
    }

    private static CSSReaderSettings readerSettings() {
        // Parser diagnostics can contain submitted content; do not log that content.
        return new CSSReaderSettings().setCustomErrorHandler(new DoNothingCSSParseErrorHandler())
                .setCustomExceptionHandler(new DoNothingCSSParseExceptionCallback())
                .setInterpretErrorHandler(new DoNothingCSSInterpretErrorHandler());
    }

    private static String escapeBoundary(String css) {
        return css.replace("<", "\\3c ");
    }

    private static void cleanRules(AbstractHasTopLevelRules container, int depth) {
        for (int i = container.getRuleCount() - 1; i >= 0; i--) {
            if (!cleanRule(container.getRuleAtIndex(i), depth)) {
                container.removeRule(i);
            }
        }
    }

    private static boolean cleanRule(Object rule, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("CSS nesting exceeds 32 levels");
        }
        if (rule instanceof CSSStyleRule style) {
            cleanDeclarations(style);
            for (int i = style.getRuleCount() - 1; i >= 0; i--) {
                if (!cleanRule(style.getRuleAtIndex(i), depth + 1)) {
                    style.removeRule(i);
                }
            }
            return true;
        }
        if (rule instanceof CSSMediaRule || rule instanceof CSSSupportsRule || rule instanceof CSSLayerRule) {
            cleanRules((AbstractHasTopLevelRules) rule, depth + 1);
            return true;
        }
        if (rule instanceof CSSNestedDeclarations nested) {
            cleanDeclarations(nested);
            return true;
        }
        // Reject unknown at-rules, fonts, animation and external resource declarations.
        return false;
    }

    private static void cleanDeclarations(IHasCSSDeclarations<?> declarations) {
        cleanDeclarations(declarations, false);
    }

    private static void cleanDeclarations(IHasCSSDeclarations<?> declarations, boolean output) {
        for (int i = declarations.getDeclarationCount() - 1; i >= 0; i--) {
            var declaration = declarations.getDeclarationAtIndex(i);
            var property = declaration.getProperty().toLowerCase(Locale.ROOT);
            boolean permitted = CssSchema.DEFAULT.allowedProperties().contains(property)
                    || MODERN_PROPERTIES.contains(property) || property.matches("--[a-zA-Z0-9_-]+")
                    || property.matches("(?:margin|padding|border|text|font)-(?:inline|block)(?:-[a-z-]+)?")
                    || (output && SVG_PROPERTIES.contains(property));
            if (!permitted || !safeExpression(declaration.getExpression(), 0, output)) {
                declarations.removeDeclaration(i);
            }
        }
    }

    private static boolean safeExpression(CSSExpression expression, int depth, boolean output) {
        if (depth > 32) {
            return false;
        }
        return expression.getAllMembers().stream().allMatch(member -> safeMember(member, depth + 1, output));
    }

    private static boolean safeMember(Object member, int depth, boolean output) {
        if (depth > 32) {
            return false;
        }
        if (member instanceof CSSExpressionMemberFunction function) {
            String name = function.getFunctionName().toLowerCase(Locale.ROOT);
            return (FUNCTIONS.contains(name) || output && "rect".equals(name))
                    && (!function.hasExpression() || safeExpression(function.getExpression(), depth + 1, output));
        }
        if (member instanceof CSSExpressionMemberMath math) {
            return math.getAllMembers().stream().allMatch(child -> safeMember(child, depth + 1, output));
        }
        if (member instanceof CSSExpressionMemberMathProduct product) {
            return product.getAllMembers().stream().allMatch(child -> safeMember(child, depth + 1, output));
        }
        if (member instanceof CSSExpressionMemberMathUnitProduct unit) {
            return safeMember(unit.getProduct(), depth + 1, output);
        }
        if (member instanceof CSSExpressionMemberTermURI uri) {
            return output && SvgOutputPolicy.localReference(uri.getURIString());
        }
        // URI members and unknown function types fail closed, including in custom-property values.
        return member instanceof CSSExpressionMemberTermSimple || member instanceof CSSExpressionMemberMathUnitSimple
                || member instanceof ECSSMathOperator || member instanceof ECSSExpressionOperator
                || member instanceof CSSExpressionMemberLineNames;
    }
}
