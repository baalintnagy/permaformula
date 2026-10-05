package org.permmath.url;

import static java.net.URLDecoder.decode;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.BooleanUtils.isNotTrue;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.springframework.util.CollectionUtils.isEmpty;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.permmath.PermMathProperties;
import org.permmath.compression.StringCompressionService;
import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import tools.jackson.databind.ObjectMapper;

import org.jspecify.annotations.NonNull;

@Validated
@Service
public class UrlService {

    @Autowired
    private PermMathProperties permMathProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringCompressionService stringCompressionService;

    public String getImageUrl(
            @NotBlank String formula,
            @Nullable @ValidImageWidth Integer imageWidth,
            @Nullable List<@NotBlank String> styleRefs,
            @Nullable String style,
            @Nullable Boolean compressUrlQuery) throws IOException {

        var imageUrlQuery = Stream.of(
                urlEncodeParamValue("formula", formula),
                urlEncodeParamValue("imageWidth", imageWidth),
                urlEncodeStyleRefs(styleRefs),
                urlEncodeParamValue("style", style))
            .filter(Objects::nonNull)
            .collect(joining("&"));
        if (isNotTrue(compressUrlQuery)) {
            return "%s/image?%s".formatted(permMathProperties.host(), imageUrlQuery);
        }

        var imageJson = objectMapper.writeValueAsString(
                new ImageDescriptor(
                        sanitizeMandatoryParamValue(formula),
                        imageWidth,
                        Optional.ofNullable(styleRefs)
                                .stream()
                                .flatMap(Collection::stream)
                                .map(UrlService::sanitizeMandatoryParamValue)
                                .toList(),
                        sanitizeParamValue(style)));
        return "%s/image-cq?%s".formatted(
                permMathProperties.host(),
                urlEncodeParamValue("ubcq", stringCompressionService.compress(imageJson)));
    }

    // Request parameters have already been URL-decoded by Spring.
    public ImageDescriptor decodeUbcq(@NotBlank String ubcq) throws IOException {
        var json = stringCompressionService.decompress(ubcq);
        return objectMapper.readValue(json, ImageDescriptor.class);
    }

    public String urlDecode(@NotNull URL url) {
        return urlDecode(url.getQuery());
    }

    private static String urlDecode(@NotNull String url) {
        return decode(url, UTF_8);
    }

    private static String urlEncodeStyleRefs(@Nullable List<@NonNull String> styleRefs) {
        if (isEmpty(styleRefs)) {
            return null;
        }
        return styleRefs.stream()
                .map(sr -> urlEncodeParamValue("styleRefs", sr))
                .filter(Objects::nonNull) // should not be necessary
                .collect(joining("&"));
    }

    private static String urlEncodeParamValue(@NonNull String paramName, @Nullable Object paramValue) {
        Objects.requireNonNull(paramName, "paramName");
        if (paramValue == null) {
            return null;
        }

        var stringifiedParamValue = paramValue.toString();
        if (stringifiedParamValue.isBlank()) {
            return null;
        }
        return "%s=%s".formatted(
                paramName,
                encode(sanitizeMandatoryParamValue(stringifiedParamValue), UTF_8));
    }

    private static String sanitizeParamValue(@Nullable String input) {
        if (isBlank(input)) {
            return null;
        }
        return sanitizeMandatoryParamValue(input);
    }

    private static String sanitizeMandatoryParamValue(@NonNull String input) {
        Objects.requireNonNull(input, "input");
        return input.replaceAll("\\s+", " ");
    }
}
