package org.permmath.url;

import static java.net.URLDecoder.decode;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.BooleanUtils.isNotTrue;
import static org.springframework.util.CollectionUtils.isEmpty;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import org.permmath.PermMathProperties;
import org.permmath.compression.StringCompressionService;
import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.NonNull;

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

        String imageUrlQuery = Stream.of(
                urlEncodeParamValue("formula", formula),
                urlEncodeParamValue("imageWidth", imageWidth),
                urlEncodeStyleRefs(styleRefs),
                urlEncodeParamValue("style", style))
            .filter(Objects::nonNull)
            .collect(joining("&"));
        if (isNotTrue(compressUrlQuery)) {
            return String.format("%s/image?%s", permMathProperties.getHost(), imageUrlQuery);
        }

        var imageJson = objectMapper.writeValueAsString(
                new ImageDescriptor(formula, imageWidth, styleRefs, style));
        return String.format("%s/image-cq?%s",
                permMathProperties.getHost(),
                urlEncodeParamValue("ubcq", stringCompressionService.compress(imageJson)));
    }

    public ImageDescriptor decodeUbcq(@NotBlank String ubcq) throws IOException {
        String json = stringCompressionService.decompress(urlDecode(ubcq));
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

    private static <T extends Object> String urlEncodeParamValue(@NonNull String paramName, @Nullable T paramValue) {
        if (paramValue == null) {
            return null;
        }

        var stringifiedParamValue = paramValue.toString();
        if (stringifiedParamValue.isBlank()) {
            return null;
        }
        return String.format("%s=%s",
                paramName,
                encode(
                    sanitizeParamValue(stringifiedParamValue), UTF_8));
    }

    private static String sanitizeParamValue(@NonNull String input) {
        return input.replaceAll("\\s+", " ");
    }
}
