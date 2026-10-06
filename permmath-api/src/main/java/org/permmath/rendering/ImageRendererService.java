package org.permmath.rendering;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.permmath.PermMathProperties;
import org.permmath.url.ImageDescriptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Validated
@Service
public class ImageRendererService implements AutoCloseable {
    private final PermMathProperties properties;
    private final RenderGateway gateway;
    private final String template;
    @org.springframework.beans.factory.annotation.Autowired
    private ApiWorkService work = new ApiWorkService(ApiWorkProperties.defaults());

    public ImageRendererService(PermMathProperties properties, RenderGateway gateway) throws IOException {
        this.properties = properties;
        this.gateway = gateway;
        try (var input = getClass().getResourceAsStream("/wrapper.html")) {
            template = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public InputStreamResource getImage(@Valid @NotNull ImageDescriptor descriptor) throws IOException {
        PreparationComplexity.html(descriptor.forumla());
        PreparationComplexity.css(descriptor.style() == null ? "" : descriptor.style());
        var externalCss = descriptor.styleRefs() == null || descriptor.styleRefs().isEmpty()
                ? java.util.List.<String>of()
                : work.fetch(() -> new StylesheetFetcher().fetchAll(descriptor.styleRefs()));
        var document = RenderContentPolicy.document(template, descriptor, externalCss);
        int width = Objects.requireNonNullElse(descriptor.imageWidth(), properties.defaultImageWidth());
        return new InputStreamResource(new ByteArrayInputStream(gateway.renderHtml(document, width)));
    }

    @Override public void close() { /* The gateway owns its transport lifecycle. */ }
}
