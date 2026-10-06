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
import jakarta.validation.constraints.NotBlank;
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
    @org.springframework.beans.factory.annotation.Autowired
    private ApiWorkService work = new ApiWorkService(ApiWorkProperties.defaults());


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
        var prepared = work.prepare(() -> {
            var effective = new ImageDescriptor(descriptor.forumla(),
                Objects.requireNonNullElse(descriptor.imageWidth(), defaults.defaultImageWidth()),
                Objects.requireNonNullElse(descriptor.styleRefs(), List.of()),
                Objects.requireNonNullElse(descriptor.style(), ""));
            PreparationComplexity.html(effective.forumla());
            PreparationComplexity.css(effective.style());
            return new Prepared(effective, key("html-svg-v9:complexity-policy-2:html-to-image-1.11.13:presentation-policy-3:" + SvgOutputPolicy.VERSION, effective));
        });
        return store.getOrGenerate(prepared.key(), () -> work.generate(() -> {
            try (var input = htmlRenderer.getImage(prepared.descriptor()).getInputStream()) {
                return SvgOutputPolicy.sanitize(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }));
    }

    public String getImg(@NotBlank String formula) throws IOException {
        var cacheKey = work.prepare(() -> {
            org.permmath.validation.InputLimits.formula(formula);
            org.permmath.contract.ComplexityPolicy.CURRENT.checkMathInput(formula);
            var identity = new ImageDescriptor(formula, null, null, null);
            return key("formula-svg-v4:complexity-policy-2:mathjax-3.2.2:" + SvgOutputPolicy.VERSION, identity);
        });
        return store.getOrGenerate(cacheKey,
                () -> work.generate(() -> SvgOutputPolicy.sanitize(formulaRenderer.render(formula))));
    }

    private String key(String format, ImageDescriptor descriptor) throws IOException {
        // Stable versioned content identity, also suitable for filenames or database keys.
        var identity = format + ":" + org.permmath.contract.ComplexityPolicy.CURRENT.fingerprint()
                + "\n" + mapper.writeValueAsString(descriptor);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Prepared(ImageDescriptor descriptor, String key) { }
}
