package org.permmath.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.permmath.PermMathProperties;
import org.permmath.rendering.cache.GeneratedImageStore;
import org.permmath.url.ImageDescriptor;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.ObjectMapper;

@Service
@Validated
public class GeneratedImageService {
    private final GeneratedImageStore store;
    private final ImageRendererService htmlRenderer;
    private final FormulaSvgRenderer formulaRenderer;
    private final PermMathProperties defaults;
    private final ObjectMapper mapper;

    public GeneratedImageService(GeneratedImageStore store, ImageRendererService htmlRenderer,
            FormulaSvgRenderer formulaRenderer, PermMathProperties defaults,
            ObjectMapper mapper) {
        this.store = store;
        this.htmlRenderer = htmlRenderer;
        this.formulaRenderer = formulaRenderer;
        this.defaults = defaults;
        this.mapper = mapper;
    }

    public String getImage(@Valid @NotNull ImageDescriptor descriptor) throws IOException {
        var effective = new ImageDescriptor(descriptor.forumla(),
                Objects.requireNonNullElse(descriptor.imageWidth(), defaults.defaultImageWidth()),
                Objects.requireNonNullElse(descriptor.styleRefs(), List.of()),
                Objects.requireNonNullElse(descriptor.style(), ""));
        return store.getOrGenerate(key("html-svg-v1", effective), () -> {
            try (var input = htmlRenderer.getImage(effective).getInputStream()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        });
    }

    public String getImg(@NotNull ImageDescriptor descriptor) throws IOException {
        if (descriptor.forumla().isBlank()) throw new IllegalArgumentException("Formula must not be blank");
        // Layout and CSS do not affect the formula-only renderer.
        var effective = new ImageDescriptor(descriptor.forumla(), null, null, null);
        return store.getOrGenerate(key("formula-svg-v1:mathjax-3.2.2", effective),
                () -> formulaRenderer.render(effective.forumla()));
    }

    private String key(String format, ImageDescriptor descriptor) throws IOException {
        // Stable versioned content identity, also suitable for filenames or database keys.
        var identity = format + "\n" + mapper.writeValueAsString(descriptor);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
