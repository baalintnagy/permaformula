package org.permaformula.rendering;

import static org.permaformula.validation.QuickValidator.coalesce;
import static org.permaformula.validation.QuickValidator.notNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.permaformula.PermaFormulaProperties;
import org.permaformula.url.ImageDescriptor;
import org.permaformula.validation.ValidDescriptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
@RequiredArgsConstructor
public class ImageRendererService implements AutoCloseable {
    private final PermaFormulaProperties properties;
    private final RenderGateway gateway;
    private final String template = loadTemplate();
    private final ApiWorkService work;
    private final StylesheetFetcher stylesheetFetcher;

    private static String loadTemplate() {
        try (InputStream input = ImageRendererService.class.getResourceAsStream("/wrapper.html")) {
            return new String(notNull(input,
                "Missing renderer template")
                .readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot load renderer template", failure);
        }
    }

    public InputStreamResource getImage(@ValidDescriptor @Valid @NotNull ImageDescriptor descriptor)
        throws IOException {
        List<String> externalCss;
        try (GenerationTiming _ = new GenerationTiming("stylesheet_fetch")) {
            externalCss = descriptor.styleRefs() == null
                || descriptor.styleRefs()
                    .isEmpty()
                        ? List
                            .<String>of()
                        : work.fetch(() -> stylesheetFetcher.fetchAll(descriptor.styleRefs()));
        }
        String document;
        try (GenerationTiming _ = new GenerationTiming("html_prepare")) {
            document = RenderContentPolicy.document(template, descriptor, externalCss);
        }
        int width = coalesce(descriptor.imageWidth(),
            properties::defaultImageWidth);
        try (GenerationTiming _ = new GenerationTiming("html_rpc")) {
            return new InputStreamResource(
                new ByteArrayInputStream(gateway.renderHtml(document, width)));
        }
    }

    @Override
    public void close() {
        /* The gateway owns its transport lifecycle. */ }
}
