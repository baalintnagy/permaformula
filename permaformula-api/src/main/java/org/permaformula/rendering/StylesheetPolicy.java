package org.permaformula.rendering;

import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_BLOCK_DEPTH;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_FUNCTION_DEPTH;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_GRID_REPEAT;
import static org.permaformula.contract.ComplexityPolicy.Limit.CSS_SELECTOR_CHARS;
import static org.permaformula.validation.QuickValidator.notNull;
import static org.permaformula.validation.QuickValidator.validate;
import static org.permaformula.validation.QuickValidator.violation;

import java.util.Locale;
import java.util.Set;

import com.helger.collection.commons.ICommonsList;
import com.helger.css.decl.AbstractHasTopLevelRules;
import com.helger.css.decl.CSSDeclaration;
import com.helger.css.decl.CSSDeclarationList;
import com.helger.css.decl.CSSExpression;
import com.helger.css.decl.CSSExpressionMemberFunction;
import com.helger.css.decl.CSSExpressionMemberLineNames;
import com.helger.css.decl.CSSExpressionMemberMath;
import com.helger.css.decl.CSSExpressionMemberMathProduct;
import com.helger.css.decl.CSSExpressionMemberMathUnitProduct;
import com.helger.css.decl.CSSExpressionMemberMathUnitSimple;
import com.helger.css.decl.CSSExpressionMemberTermSimple;
import com.helger.css.decl.CSSExpressionMemberTermURI;
import com.helger.css.decl.CSSLayerRule;
import com.helger.css.decl.CSSMediaRule;
import com.helger.css.decl.CSSNestedDeclarations;
import com.helger.css.decl.CSSSelector;
import com.helger.css.decl.CSSStyleRule;
import com.helger.css.decl.CSSSupportsRule;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.decl.ECSSExpressionOperator;
import com.helger.css.decl.ECSSMathOperator;
import com.helger.css.decl.ICSSExpressionMember;
import com.helger.css.decl.IHasCSSDeclarations;
import com.helger.css.decl.visit.CSSVisitor;
import com.helger.css.decl.visit.DefaultCSSVisitor;
import com.helger.css.handler.DoNothingCSSParseExceptionCallback;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderDeclarationList;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.DoNothingCSSInterpretErrorHandler;
import com.helger.css.reader.errorhandler.DoNothingCSSParseErrorHandler;
import com.helger.css.reader.errorhandler.ICSSInterpretErrorHandler;
import com.helger.css.reader.errorhandler.ThrowingCSSParseErrorHandler;
import com.helger.css.writer.CSSWriter;
import com.helger.css.writer.CSSWriterSettings;
import org.apache.commons.lang3.StringUtils;
import org.owasp.html.CssSchema;
import org.permaformula.contract.ComplexityPolicy;

/**
 * Parsed presentation CSS, including variables, responsive rules and modern layout.
 */
final class StylesheetPolicy {
    private static final ComplexityPolicy LIMITS = ComplexityPolicy.CURRENT;

    private static final Set<String> MODERN_PROPERTIES = Set.of(
        "display", "position", "inset", "top", "right", "bottom", "left", "z-index",
        "box-sizing", "gap", "row-gap", "column-gap", "flex", "flex-basis", "flex-grow",
        "flex-shrink",
        "flex-direction", "flex-wrap", "align-items", "align-self", "align-content",
        "justify-content",
        "justify-items", "justify-self", "order", "grid", "grid-template",
        "grid-template-columns",
        "grid-template-rows", "grid-template-areas", "grid-auto-columns", "grid-auto-rows",
        "grid-auto-flow",
        "grid-column", "grid-row", "grid-area", "object-fit", "object-position", "aspect-ratio",
        "opacity", "transform", "transform-origin", "box-shadow", "text-shadow",
        "border-radius",
        "overflow", "overflow-x", "overflow-y", "text-overflow", "overflow-wrap", "word-break",
        "background", "background-image", "background-size", "background-position",
        "background-repeat",
        "content", "color-scheme", "vertical-align", "-webkit-font-smoothing",
        "-moz-osx-font-smoothing");

    private static final Set<String> FUNCTIONS = Set.of("var", "calc", "min", "max", "clamp", "env",
        "rgb", "rgba", "hsl", "hsla", "hwb", "lab", "lch", "oklab", "oklch", "color",
        "color-mix",
        "linear-gradient", "radial-gradient", "conic-gradient", "repeating-linear-gradient",
        "repeating-radial-gradient", "repeating-conic-gradient", "translate", "translatex",
        "translatey",
        "translatez", "translate3d", "scale", "scalex", "scaley", "scalez", "scale3d", "rotate",
        "rotatex",
        "rotatey", "rotatez", "rotate3d", "skew", "skewx", "skewy", "matrix", "matrix3d",
        "perspective",
        "repeat", "minmax", "fit-content");

    private static final CSSWriterSettings WRITER = new CSSWriterSettings(true);

    private static final Set<String> SVG_PROPERTIES = Set.of("fill", "fill-rule", "fill-opacity",
        "stroke",
        "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin",
        "stroke-miterlimit",
        "stroke-dasharray", "stroke-dashoffset", "clip-path", "clip-rule", "text-anchor",
        "dominant-baseline",
        "alignment-baseline", "baseline-shift", "vector-effect", "paint-order",
        "shape-rendering",
        "clip");

    /** Preserve vector paint and local glyph/clip references in exported presentation styles. */
    static String outputDeclarations(String source) {
        if (!StringUtils.isNotBlank(source)) {
            return "";
        }
        // The stylesheet parser supports empty custom properties emitted by getComputedStyle;
        // the standalone declaration-list parser does not. Never emit the wrapper or extra rules.
        CascadingStyleSheet sheet = parse(".output{%s}".formatted(source), false);
        validate(
            !(sheet.getRuleCount() != 1
                || sheet.getImportRuleCount() != 0
                || sheet.getNamespaceRuleCount() != 0),
            "Invalid SVG presentation style");
        if (!(sheet.getRuleAtIndex(0) instanceof CSSStyleRule declarations)
            || declarations.getRuleCount() != 0) {
            throw violation("Invalid SVG presentation style");
        }
        cleanDeclarations(declarations, true);
        CSSDeclarationList result = new CSSDeclarationList();
        declarations.getAllDeclarations()
            .forEach(result::addDeclaration);
        return escapeBoundary(result.getAsCSSString(WRITER, 0));
    }

    /** Downloaded library CSS remains intact; CSP denies browser resource requests. */
    static String externalStylesheet(String source) {
        CascadingStyleSheet sheet = parse(source);
        validate(sheet.getImportRuleCount() == 0,
            "Unexpanded external CSS imports");
        CSSVisitor.visitCSS(sheet, new DefaultCSSVisitor() {
            @Override
            public void onStyleRuleSelector(CSSSelector selector) {
                if (selector.getAsCSSString(WRITER, 0)
                    .length() > LIMITS.get(CSS_SELECTOR_CHARS)) {
                    PreparationComplexity.reject("CSS selector length");
                }
            }

            @Override
            public void onDeclaration(CSSDeclaration declaration) {
                externalExpression(declaration.getExpression(), 0);
            }
        });
        return escapeBoundary(new CSSWriter(WRITER)
            .getCSSAsString(sheet));
    }

    static String stylesheet(String source) {
        if (!StringUtils.isNotBlank(source)) {
            return "";
        }
        CascadingStyleSheet sheet = parse(source);
        // Imports must be expanded by the controlled fetcher, never the browser.
        validate(sheet.getImportRuleCount() == 0
            && sheet.getNamespaceRuleCount() == 0,
            "Unexpanded CSS imports or namespaces");
        cleanRules(sheet, 0);
        return escapeBoundary(new CSSWriter(WRITER)
            .getCSSAsString(sheet));
    }

    static String declarations(String source) {
        PreparationComplexity.css(source);
        CSSDeclarationList declarations = notNull(CSSReaderDeclarationList.readFromString(source,
            readerSettings(true)),
            "Invalid CSS declarations");
        cleanDeclarations(declarations);
        return escapeBoundary(declarations.getAsCSSString(WRITER, 0));
    }

    static CascadingStyleSheet parse(String source) {
        return parse(source, true);
    }

    private static CascadingStyleSheet parse(String source, boolean strict) {
        PreparationComplexity.css(source);
        CascadingStyleSheet sheet = CSSReader.readFromStringReader(source, readerSettings(strict));
        validate(
            !(sheet == null), "Invalid CSS stylesheet");
        return sheet;
    }

    private static CSSReaderSettings readerSettings(boolean strict) {
        // Parser diagnostics can contain submitted content; do not log that content.
        return new CSSReaderSettings()
            .setCustomErrorHandler(strict
                ? new ThrowingCSSParseErrorHandler()
                : new DoNothingCSSParseErrorHandler())
            .setCustomExceptionHandler(new DoNothingCSSParseExceptionCallback())
            .setInterpretErrorHandler(strict ? new ICSSInterpretErrorHandler() {
                @Override
                public void onCSSInterpretationWarning(String message) {
                    throw violation("Unsupported CSS syntax");
                }

                @Override
                public void onCSSInterpretationError(String message) {
                    throw violation("Invalid CSS syntax");
                }
            } : new DoNothingCSSInterpretErrorHandler());
    }

    private static String escapeBoundary(String css) {
        return css.replace("<", "\\3c ");
    }

    private static void cleanRules(AbstractHasTopLevelRules container, int depth) {
        for (int i = container.getRuleCount() - 1; i >= 0; i--) {
            if (!cleanRule(container.getRuleAtIndex(i), depth)) {
                throw violation("Unsupported CSS rule");
            }
        }
    }

    private static boolean cleanRule(Object rule, int depth) {
        if (depth > LIMITS.get(CSS_BLOCK_DEPTH)) {
            PreparationComplexity.reject("CSS nesting");
        }
        if (rule instanceof CSSStyleRule style) {
            if (style.getAllSelectors()
                .stream()
                .anyMatch(selector -> selector.getAsCSSString(WRITER, 0)
                    .length() > LIMITS.get(CSS_SELECTOR_CHARS))) {
                PreparationComplexity.reject("CSS selector length");
            }
            cleanDeclarations(style);
            for (int i = style.getRuleCount() - 1; i >= 0; i--) {
                if (!cleanRule(style.getRuleAtIndex(i), depth + 1)) {
                    style.removeRule(i);
                }
            }
            return true;
        }
        if (rule instanceof AbstractHasTopLevelRules nested
            && (rule instanceof CSSMediaRule
                || rule instanceof CSSSupportsRule
                || rule instanceof CSSLayerRule)) {
            cleanRules(nested, depth + 1);
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
            CSSDeclaration declaration = declarations.getDeclarationAtIndex(i);
            String property = declaration.getProperty()
                .toLowerCase(Locale.ROOT);
            boolean permitted = CssSchema.DEFAULT.allowedProperties()
                .contains(property)
                || MODERN_PROPERTIES.contains(property)
                || property.matches("--[a-zA-Z0-9_-]+")
                || property.matches(
                    "(?:margin|padding|border|text|font)-(?:inline|block)(?:-[a-z-]+)?")
                || (output
                    && SVG_PROPERTIES.contains(property));
            if (!permitted
                || !safeExpression(declaration.getExpression(), 0, output)) {
                validate(output,
                    "Unsupported CSS property or value");
                declarations.removeDeclaration(i);
            }
        }
    }

    private static boolean safeExpression(CSSExpression expression, int depth, boolean output) {
        if (depth > LIMITS.get(CSS_FUNCTION_DEPTH)) {
            return false;
        }
        return expression.getAllMembers()
            .stream()
            .allMatch(member -> safeMember(member, depth + 1, output));
    }

    private static void validateGridRepetition(CSSExpressionMemberFunction function) {
        if ("repeat".equals(function.getFunctionName()
            .toLowerCase(Locale.ROOT))
            && function.hasExpression()) {
            ICommonsList<ICSSExpressionMember> members = function.getExpression()
                .getAllMembers();
            if (!members.isEmpty()
                && members.get(0) instanceof CSSExpressionMemberTermSimple term) {
                String value = term.getValue();
                if (!value.equalsIgnoreCase("auto-fill")
                    && !value.equalsIgnoreCase("auto-fit")) {
                    try {
                        double repeats = Double.parseDouble(value);
                        if (!Double.isFinite(repeats)
                            || repeats > LIMITS.get(CSS_GRID_REPEAT)) {
                            PreparationComplexity.reject("CSS grid repetition");
                        }
                    } catch (NumberFormatException _) {
                        PreparationComplexity.reject("CSS grid repetition");
                    }
                }
            }
        }
    }

    private static void externalExpression(Object member, int depth) {
        if (depth > LIMITS.get(CSS_FUNCTION_DEPTH)) {
            PreparationComplexity.reject("CSS function nesting");
        }
        if (member instanceof CSSExpression expression) {
            expression.getAllMembers()
                .forEach(child -> externalExpression(child, depth + 1));
        } else if (member instanceof CSSExpressionMemberFunction function) {
            validateGridRepetition(function);
            if (function.hasExpression()) {
                externalExpression(function.getExpression(), depth + 1);
            }
        } else if (member instanceof CSSExpressionMemberMath math) {
            math.getAllMembers()
                .forEach(child -> externalExpression(child, depth + 1));
        } else if (member instanceof CSSExpressionMemberMathProduct product) {
            product.getAllMembers()
                .forEach(child -> externalExpression(child, depth + 1));
        } else if (member instanceof CSSExpressionMemberMathUnitProduct unit) {
            externalExpression(unit.getProduct(), depth + 1);
        }
    }

    private static boolean safeMember(Object member, int depth, boolean output) {
        if (depth > LIMITS.get(CSS_FUNCTION_DEPTH)) {
            return false;
        }
        if (member instanceof CSSExpressionMemberFunction function) {
            String name = function.getFunctionName()
                .toLowerCase(Locale.ROOT);
            validateGridRepetition(function);
            return (FUNCTIONS.contains(name)
                || output
                    && "rect".equals(name))
                && (!function.hasExpression()
                    || safeExpression(function.getExpression(), depth + 1, output));
        }
        if (member instanceof CSSExpressionMemberMath math) {
            return math.getAllMembers()
                .stream()
                .allMatch(child -> safeMember(child, depth + 1, output));
        }
        if (member instanceof CSSExpressionMemberMathProduct product) {
            return product.getAllMembers()
                .stream()
                .allMatch(child -> safeMember(child, depth + 1, output));
        }
        if (member instanceof CSSExpressionMemberMathUnitProduct unit) {
            return safeMember(unit.getProduct(), depth + 1, output);
        }
        if (member instanceof CSSExpressionMemberTermURI uri) {
            return output
                && SvgOutputPolicy.localReference(uri.getURIString());
        }
        // URI members and unknown function types fail closed, including in custom-property values.
        return member instanceof CSSExpressionMemberTermSimple
            || member instanceof CSSExpressionMemberMathUnitSimple
            || member instanceof ECSSMathOperator
            || member instanceof ECSSExpressionOperator
            || member instanceof CSSExpressionMemberLineNames;
    }
}
