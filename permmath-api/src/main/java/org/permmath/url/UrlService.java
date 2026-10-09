package org.permmath.url;

import java.io.IOException;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.permmath.compression.StringCompressionService;
import org.permmath.rendering.ApiWorkProperties;
import org.permmath.rendering.ApiWorkService;
import org.permmath.validation.InputLimits;
import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Validated
@Service
public class UrlService {

    private static final ObjectMapper DESCRIPTOR_MAPPER = JsonMapper.builder(
            JsonFactory.builder().streamReadConstraints(
                    StreamReadConstraints.builder()
                            .maxNestingDepth(8)
                            .maxTokenCount(128)
                            .maxNameLength(64)
                            .maxStringLength(InputLimits.MAX_CONTENT_BYTES)
                            .maxNumberLength(10)
                            .build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringCompressionService stringCompressionService;
    @Autowired
    private ApiWorkService work = new ApiWorkService(
            ApiWorkProperties.defaults());

    public String getUbcq(
            @NotBlank String formula,
            @Nullable @ValidImageWidth Integer imageWidth,
            @Nullable List<@NotBlank String> styleRefs,
            @Nullable String style) throws IOException {
        return work.prepare(() -> createUbcq(formula, imageWidth, styleRefs, style));
    }

    private String createUbcq(String formula, Integer imageWidth, List<String> styleRefs, String style) throws IOException {

        var imageJson = objectMapper.writeValueAsString(
                new ImageDescriptor(
                        formula,
                        imageWidth,
                        styleRefs,
                        style));
        return stringCompressionService.compress(imageJson);
    }

    // Request parameters have already been URL-decoded by Spring.
    public ImageDescriptor decodeUbcq(@NotBlank String ubcq) throws IOException {
        return work.prepare(() -> decode(ubcq));
    }

    private ImageDescriptor decode(String ubcq) {
        try {
            var json = stringCompressionService.decompress(ubcq);
            var descriptor = DESCRIPTOR_MAPPER.readValue(json, ImageDescriptor.class);
            if (descriptor == null) {
                throw new IllegalArgumentException("Descriptor must not be null");
            }
            return descriptor;
        } catch (JacksonException invalid) {
            // Parser diagnostics may contain submitted content; do not expose/log them.
            throw new IllegalArgumentException("Invalid image descriptor");
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid compressed payload");
        }
    }

}
