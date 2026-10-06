package org.permmath.url;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.permmath.PermMathProperties;
import org.permmath.compression.StringCompressionService;
import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import tools.jackson.databind.ObjectMapper;


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
                        formula,
                        imageWidth,
                        styleRefs,
                        style));
        return "%s/image?%s".formatted(
                permMathProperties.host(),
                "ubcq=" + encode(stringCompressionService.compress(imageJson), UTF_8));
    }

    // Request parameters have already been URL-decoded by Spring.
    public ImageDescriptor decodeUbcq(@NotBlank String ubcq) throws IOException {
        var json = stringCompressionService.decompress(ubcq);
        return objectMapper.readValue(json, ImageDescriptor.class);
    }

}
