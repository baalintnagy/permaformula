package org.permaformula.url;

import java.io.IOException;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.permaformula.compression.StringCompressionService;
import org.permaformula.rendering.ApiWorkService;
import org.permaformula.validation.InputLimits;
import org.permaformula.validation.QuickValidator;
import org.permaformula.validation.ValidCompose;
import org.permaformula.validation.ValidDescriptor;
import org.permaformula.validation.ValidEncoded;
import org.permaformula.validation.ValidImageWidth;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Validated
@Service
@RequiredArgsConstructor
public class UrlService {

    private static final ObjectMapper DESCRIPTOR_MAPPER = JsonMapper.builder(
        JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(8)
                .maxTokenCount(128)
                .maxNameLength(64)
                .maxStringLength(InputLimits.MAX_CONTENT_BYTES)
                .maxNumberLength(10)
                .build())
            .build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    private final ObjectMapper objectMapper;

    private final StringCompressionService stringCompressionService;
    private final ApiWorkService work;

    @ValidCompose
    public String getUbcq(
        @NotBlank String formula,
        @Nullable @ValidImageWidth Integer imageWidth,
        @Nullable List<@NotBlank String> styleRefs,
        @Nullable String style) throws IOException {
        return work.prepare(() -> createUbcq(formula, imageWidth, styleRefs, style));
    }

    private String createUbcq(String formula, Integer imageWidth, List<String> styleRefs,
        String style) throws IOException {

        String imageJson = objectMapper.writeValueAsString(
            new ImageDescriptor(
                formula,
                imageWidth,
                styleRefs,
                style));
        return stringCompressionService.compress(imageJson);
    }

    // Request parameters have already been URL-decoded by Spring.
    @ValidDescriptor(presentation = false)
    public ImageDescriptor decodeUbcq(
        @ValidEncoded @NotBlank @Size(max = InputLimits.MAX_ENCODED_CHARS) String ubcq)
        throws IOException {
        return work.prepare(() -> decode(ubcq));
    }

    private ImageDescriptor decode(String ubcq) {
        try {
            String json = stringCompressionService.decompress(ubcq);
            ImageDescriptor descriptor = DESCRIPTOR_MAPPER.readValue(json, ImageDescriptor.class);
            QuickValidator.validate(
                !(descriptor == null),
                () -> QuickValidator.violation("Descriptor must not be null"));
            return descriptor;
        } catch (JacksonException _) {
            // Parser diagnostics may contain submitted content; do not expose/log them.
            throw QuickValidator.violation("Invalid image descriptor");
        } catch (IOException _) {
            throw QuickValidator.violation("Invalid compressed payload");
        }
    }

}
