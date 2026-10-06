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

    private static final ObjectMapper DESCRIPTOR_MAPPER = tools.jackson.databind.json.JsonMapper.builder(
            tools.jackson.core.json.JsonFactory.builder().streamReadConstraints(
                    tools.jackson.core.StreamReadConstraints.builder()
                            .maxNestingDepth(8)
                            .maxTokenCount(128)
                            .maxNameLength(64)
                            .maxStringLength(org.permmath.validation.InputLimits.MAX_CONTENT_BYTES)
                            .maxNumberLength(10)
                            .build()).build())
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

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
        try {
            var json = stringCompressionService.decompress(ubcq);
            var descriptor = DESCRIPTOR_MAPPER.readValue(json, ImageDescriptor.class);
            if (descriptor == null) {
                throw new IllegalArgumentException("Descriptor must not be null");
            }
            return descriptor;
        } catch (tools.jackson.core.JacksonException invalid) {
            // Parser diagnostics may contain submitted content; do not expose/log them.
            throw new IllegalArgumentException("Invalid image descriptor");
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid compressed payload");
        }
    }

}
