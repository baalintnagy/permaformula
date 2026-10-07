package org.permmath.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.permmath.PermMathProperties;
import org.permmath.contract.ComplexityPolicy;
import org.permmath.ratelimit.ClientRateLimits;
import org.permmath.rendering.cache.GeneratedImageStore;
import org.permmath.url.ImageDescriptor;
import org.permmath.validation.InputLimits;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

import com.google.common.hash.Hashing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.ObjectMapper;

@Service
@Validated
public class GeneratedImageService {

    private final GeneratedImageStore store;

    private final ImageRendererService htmlRenderer;

    private final FormulaSvgRenderer formulaRenderer;

    private final PermMathProperties defaults;

    private final ObjectMapper mapper;
    @Autowired
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
        return store.getOrGenerate(prepared.key(), () -> work.generate(ClientRateLimits.generation(() -> {
            try (var input = htmlRenderer.getImage(prepared.descriptor()).getInputStream()) {
                return SvgOutputPolicy.sanitize(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        })));
    }

    public String getImg(@NotBlank String formula) throws IOException {
        var cacheKey = work.prepare(() -> {
            InputLimits.formula(formula);
            ComplexityPolicy.CURRENT.checkMathInput(formula);
            var identity = new ImageDescriptor(formula, null, null, null);
            return key("formula-svg-v4:complexity-policy-2:mathjax-3.2.2:" + SvgOutputPolicy.VERSION, identity);
        });
        return store.getOrGenerate(cacheKey,
                () -> work.generate(ClientRateLimits.generation(
                        () -> SvgOutputPolicy.sanitize(formulaRenderer.render(formula)))));
    }

    private String key(String format, ImageDescriptor descriptor) {
        // Stable versioned content identity, also suitable for filenames or database keys.
        var identity = format + ":" + ComplexityPolicy.CURRENT.fingerprint()
                + "\n" + mapper.writeValueAsString(descriptor);
        return Hashing.sha256()
            .hashString(identity, StandardCharsets.UTF_8)
            .toString();
    }

    private record Prepared(ImageDescriptor descriptor, String key) { }
}
