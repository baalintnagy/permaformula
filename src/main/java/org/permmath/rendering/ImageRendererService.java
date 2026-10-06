package org.permmath.rendering;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.permmath.PermMathProperties;
import org.permmath.url.ImageDescriptor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.annotation.PreDestroy;

@Validated
@Service
public class ImageRendererService implements AutoCloseable {
    private final PermMathProperties properties;
    private final RenderingProperties rendering;
    private final String template;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("math-renderer").daemon(true).factory());
    private RenderAssets assets;
    private ChromiumRenderer chromium;

    public ImageRendererService(PermMathProperties properties, RenderingProperties rendering) throws IOException {
        this.properties = properties;
        this.rendering = rendering;
        try (var input = getClass().getResourceAsStream("/wrapper.html")) {
            template = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public InputStreamResource getImage(@Valid @NotNull ImageDescriptor descriptor) throws IOException {
        var task = worker.submit(() -> {
            if (assets == null) assets = new RenderAssets();
            var links = descriptor.styleRefs() == null ? "" : descriptor.styleRefs().stream()
                    .map(ref -> "<link rel=\"stylesheet\" href=\"%s\">".formatted(escapeAttribute(ref)))
                    .collect(java.util.stream.Collectors.joining("\n"));
            var html = template.replace("_*{STYLE_REFS}*_", links)
                    .replace("_*{STYLE}*_", Objects.requireNonNullElse(descriptor.style(), ""))
                    .replace("_*{FORMULA}*_", descriptor.forumla());
            var url = assets.add(html);
            int width = Objects.requireNonNullElse(descriptor.imageWidth(), properties.defaultImageWidth());
            try {
                if (chromium == null) chromium = new ChromiumRenderer(rendering);
                return chromium.render(url, width);
            } finally { assets.remove(url); }
        });
        try {
            return new InputStreamResource(new ByteArrayInputStream(
                    task.get(rendering.timeout().toMillis() + 5000, TimeUnit.MILLISECONDS)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            throw new IOException("Rendering interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            throw new IOException("Rendering failed using Chromium", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(true);
            throw new IOException("Rendering timed out", e);
        }
    }

    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    @PreDestroy
    @Override public void close() {
        try {
            worker.submit(() -> {
                if (chromium != null) chromium.close();
                if (assets != null) assets.close();
            }).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
          catch (Exception e) { /* Shutdown must still release the worker. */ }
        finally { worker.shutdownNow(); }
    }
}