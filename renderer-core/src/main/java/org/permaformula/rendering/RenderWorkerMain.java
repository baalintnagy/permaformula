package org.permaformula.rendering;

import static org.permaformula.validation.QuickValidator.notNull;
import static org.permaformula.validation.QuickValidator.validate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import com.google.protobuf.ByteString;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.contract.v1.RenderRequest;
import org.permaformula.contract.v1.RenderResponse;

/** Private, sequential worker protocol; untrusted content never enters executable source. */
public final class RenderWorkerMain {
    public static void main(String[] args) {
        boolean html = "html".equals(args[0]);
        long started = System.nanoTime();
        try (AsciiMathRenderer formulae = html ? null : new AsciiMathRenderer();
            RenderAssets assets = html ? new RenderAssets() : null;
            ChromiumRenderer chromium = html
                ? new ChromiumRenderer(new BrowserSettings(Path.of(args[1]),
                    Duration.ofMillis(Long.parseLong(args[2])),
                    Boolean.parseBoolean(args[3])))
                : null) {
            if (formulae != null) {
                formulae.warmUp();
            }
            RenderTimings.elapsed(html ? "html_initialize" : "formula_initialize", started);
            System.out.write(RenderWorkerPool.READY);
            System.out.flush();
            while (true) {
                RenderRequest request = WorkerMessages.read(System.in, RenderRequest.parser());
                started = System.nanoTime();
                try {
                    validate(
                        !((request.hasHtml()
                            || request.hasJpegSvg()) != html),
                        () -> new IllegalArgumentException("Wrong worker kind"));
                    byte[] svg;
                    if (html) {
                        RenderAssets activeAssets = notNull(assets);
                        ChromiumRenderer activeChromium = notNull(chromium);
                        String document = request.hasJpegSvg()
                            ? "<!doctype html><html><body></body></html>"
                            : request.getHtml()
                                .getDocument();
                        String url = activeAssets.add(document);
                        try {
                            svg = request.hasJpegSvg()
                                ? activeChromium.jpeg(url, request.getJpegSvg())
                                : activeChromium.render(url, request.getHtml()
                                    .getWidth());
                        } finally {
                            activeAssets.remove(url);
                        }
                    } else {
                        svg = notNull(formulae)
                            .render(request.getFormula()
                                .getFormula())
                            .getBytes(StandardCharsets.UTF_8);
                    }
                    validate(
                        !(svg.length == 0
                            || svg.length > ComplexityPolicy.CURRENT
                                .get(ComplexityPolicy.Limit.SVG_BYTES)),
                        () -> new IllegalArgumentException("Render output exceeds limit"));
                    System.out.write(RenderWorkerPool.OK);
                    WorkerMessages.write(System.out,
                        RenderResponse.newBuilder()
                            .setRequestId(request.getRequestId())
                            .setSvg(ByteString.copyFrom(svg))
                            .build());
                } catch (IllegalArgumentException _) {
                    System.out.write(RenderWorkerPool.INVALID);
                    System.out.flush();
                    return; // Discard any execution state affected by a failed request.
                } finally {
                    RenderTimings.elapsed(html ? "html_render" : "formula_render", started);
                }
            }
        } catch (Exception failed) {
            System.err.println("event=render_worker_failed type=%s".formatted(
                failed.getClass()
                    .getSimpleName()));
            System.exit(1);
        }
    }

    private RenderWorkerMain() {
    }
}
