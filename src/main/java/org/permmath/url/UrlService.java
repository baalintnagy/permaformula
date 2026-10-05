package org.permmath.url;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.isBlank;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.validation.constraints.NotBlank;

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
            @Nullable String style) throws IOException {

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
        return "%s/image?%s".formatted(
                permMathProperties.host(),
                "ubcq=" + encode(stringCompressionService.compress(imageJson), UTF_8));
    }

    // Request parameters have already been URL-decoded by Spring.
    public ImageDescriptor decodeUbcq(@NotBlank String ubcq) throws IOException {
        var json = stringCompressionService.decompress(ubcq);
        return objectMapper.readValue(json, ImageDescriptor.class);
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
