package org.permmath;

import static java.net.URLDecoder.decode;
import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;
import static org.springframework.util.CollectionUtils.isEmpty;

import java.net.URL;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import lombok.NonNull;

@Validated
@Service
public class UrlService {

    @Autowired
    private PermMathProperties permMathProperties;

    public String getImageUrl(@Nullable String formula,
            @Nullable @ValidImageWidth Integer imageWidth,
            @Nullable List<@NotBlank String> styleRefs, @NotBlank String style) {
        return String.format("%s/image?%s",
                permMathProperties.getHost(),
                Stream.of(
                        encodeParam("formula", formula),
                        encodeParam("imageWidth", imageWidth),
                        encodeStyleRefs(styleRefs),
                        encodeParam("style", style))
                    .filter(Objects::nonNull)
                    .collect(joining("&")));
    }

    public String decodePermathUrl(@NotNull URL url) {
        return decode(url.getQuery(), UTF_8);
    }

    private static String encodeStyleRefs(@Nullable List<@NonNull String> styleRefs) {
        if (isEmpty(styleRefs)) {
            return null;
        }
        return styleRefs.stream()
                .map(sr -> encodeParam("styleRefs", sr))
                .filter(Objects::nonNull) // should not be necessary
                .collect(joining("&"));
    }

    private static <T extends Object> String encodeParam(@NonNull String paramName, @Nullable T paramValue) {
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
                    sanitize(stringifiedParamValue), UTF_8));
    }

    private static String sanitize(@NonNull String input) {
        return input.replaceAll("\\s+", " ");
    }
}
