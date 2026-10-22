package org.permaformula.contract;

import static org.permaformula.validation.QuickValidator.validate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/** One validated policy for API checks, isolated workers and their JavaScript engines. */
public final class ComplexityPolicy {
    public enum Limit {
        HTML_ELEMENTS, HTML_DEPTH, HTML_NODES, ATTRIBUTES, INLINE_STYLE_CHARS,
        CSS_RULES, CSS_DECLARATIONS, CSS_TOKENS, CSS_BLOCK_DEPTH, CSS_FUNCTION_DEPTH,
        CSS_BRACKET_DEPTH, CSS_SELECTOR_CHARS, CSS_TOKEN_CHARS, CSS_GRID_REPEAT, CSS_HTML_WORK,
        MATH_EXPRESSIONS, MATH_CHARS, MATH_NODES, MATH_DEPTH, TEX_MACROS, TEX_BUFFER,
        RENDER_NODES, RENDER_DEPTH, RENDER_DIMENSION, RENDER_AREA, ELEMENT_POSITION,
        SVG_BYTES, NAMESPACE_DECLARATIONS, EX_PIXELS, EM_PIXELS;

        String key() {
            return name()
                .toLowerCase(Locale.ROOT)
                .replace('_', '-');
        }
    }

    public static final ComplexityPolicy CURRENT = load(System.getProperties(),
        System.getenv());
    private final Map<Limit, Integer> values;

    private ComplexityPolicy(Map<Limit, Integer> values) {
        this.values = Map.copyOf(values);
    }

    public static ComplexityPolicy load(Properties overrides, Map<String, String> environment) {
        Properties defaults = new Properties();
        try (InputStream input = ComplexityPolicy.class
            .getResourceAsStream("/complexity-defaults.properties")) {
            validate(
                !(input == null),
                () -> new IllegalStateException("Missing complexity defaults"));
            defaults.load(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load complexity defaults", failure);
        }
        EnumMap<Limit, Integer> values = Arrays.stream(Limit.values())
            .collect(Collectors.toMap(limit -> limit,
                limit -> configuredValue(limit, overrides, environment, defaults),
                (left, _) -> left, () -> new EnumMap<>(Limit.class)));
        validate(
            !(values.get(Limit.SVG_BYTES) > RenderProtocol.MAX_CONTENT_BYTES),
            () -> new IllegalArgumentException("SVG limit exceeds transport size limit"));
        return new ComplexityPolicy(values);
    }

    private static int configuredValue(Limit limit, Properties overrides,
        Map<String, String> environment,
        Properties defaults) {
        String value = overrides.getProperty("permaformula.complexity.%s".formatted(limit.key()),
            environment.getOrDefault("PERMAFORMULA_COMPLEXITY_%s".formatted(limit.name()),
                defaults.getProperty(limit.key())));
        try {
            int number = Integer.parseInt(value);
            if (number <= 0) {
                throw new NumberFormatException();
            }
            return number;
        } catch (NumberFormatException _) {
            throw new IllegalArgumentException("Invalid complexity setting: %s".formatted(
                limit.key()));
        }
    }

    public int get(Limit limit) {
        return values.get(limit);
    }

    public void checkMathInput(String source) {
        validate(
            !(source.length() > get(Limit.MATH_CHARS)),
            "Math expression exceeds size limit");
    }

    /** Keys are enum names and values validated positive integers, never submitted text. */
    public String json() {
        return Arrays.stream(Limit.values())
            .map(limit -> "\"%s\":%s".formatted(limit.name(), get(limit)))
            .collect(Collectors.joining(",", "{", "}"));
    }

    public Map<String, String> workerEnvironment() {
        return Arrays.stream(Limit.values())
            .collect(
                Collectors.toUnmodifiableMap(limit -> "PERMAFORMULA_COMPLEXITY_%s".formatted(
                    limit.name()),
                    limit -> Integer.toString(get(limit))));
    }

    public String fingerprint() {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json()
                        .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
