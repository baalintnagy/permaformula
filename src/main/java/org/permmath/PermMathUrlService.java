package org.permmath;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.springframework.util.CollectionUtils.isEmpty;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
public class PermMathUrlService {

    @Autowired
    private PermMathProperties permMathProperties;

    public String getPermMathUrl(@Nullable String formula, @Nullable List<@NotBlank String> styleRefs, @NotBlank String style) {
        return String.format("%s/image?%s",
                permMathProperties.getHost(),
                Stream.of(
                        encodeParam("formula", formula),
                        encodeStyleRefs(styleRefs),
                        encodeParam("style", style))
                    .filter(Objects::nonNull)
                    .collect(joining("&")));
    }

    private static String encodeStyleRefs(@Nullable List<@NotBlank String> styleRefs) {
        if (isEmpty(styleRefs)) {
            return null;
        }
        return styleRefs.stream()
                .map(sr -> encodeParam("styleRefs", sr))
                .filter(Objects::nonNull) // not really necessary
                .collect(joining("&"));
    }

    private static String encodeParam(@NotBlank String paramName, @Nullable String paramValue) {
        requireNonNull(paramName, "paramName");

        if (isBlank(paramValue)) {
            return null;
        }
        return String.format("%s=%s", paramName, encode(paramValue, UTF_8));
    }
}
