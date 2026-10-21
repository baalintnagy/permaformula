package org.permaformula.rendering;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import com.google.protobuf.ByteString;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.contract.v1.*;
import org.permaformula.validation.QuickValidator;

/** Private, sequential worker protocol; untrusted content never enters executable source. */
public final class RenderWorkerMain {
    public static void main(String[] args) {
        boolean html = "html".equals(args[0]);
        long started = System.nanoTime();
        try (AsciiMathRenderer formulae = html ? null : new AsciiMathRenderer();
            RenderAssets assets = html ? new RenderAssets() : null;
            ChromiumRenderer chromium = html
                ? new ChromiumRenderer(new BrowserSettings(Path.of(args[1]),
                    Duration.ofMillis(Long.parseLong(args[2])), Boolean.parseBoolean(args[3])))
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
                    QuickValidator.validate(
                        !((request.hasHtml()
                            || request.hasJpegSvg()) != html),
                        () -> new IllegalArgumentException("Wrong worker kind"));
                    byte[] svg;
                    if (request.hasJpegSvg()) {
                        String url = assets.add("<!doctype html><html><body></body></html>");
                        try {
                            svg = chromium.jpeg(url, request.getJpegSvg());
                        } finally {
                            assets.remove(url);
                        }
                    } else if (html) {
                        String url = assets.add(request.getHtml().getDocument());
                        try {
                            svg = chromium.render(url, request.getHtml().getWidth());
                        } finally {
                            assets.remove(url);
                        }
                    } else {
                        svg = formulae.render(request.getFormula().getFormula())
                            .getBytes(StandardCharsets.UTF_8);
                    }
                    QuickValidator.validate(
                        !(svg.length == 0
                            || svg.length > ComplexityPolicy.CURRENT
                                .get(ComplexityPolicy.Limit.SVG_BYTES)),
                        () -> new IllegalArgumentException("Render output exceeds limit"));
                    System.out.write(RenderWorkerPool.OK);
                    WorkerMessages.write(System.out,
                        RenderResponse.newBuilder().setRequestId(request.getRequestId())
                            .setSvg(ByteString.copyFrom(svg)).build());
                } catch (IllegalArgumentException _) {
                    System.out.write(RenderWorkerPool.INVALID);
                    System.out.flush();
                    return; // Discard any execution state affected by a failed request.
                } finally {
                    RenderTimings.elapsed(html ? "html_render" : "formula_render", started);
                }
            }
        } catch (Exception failed) {
            System.err
                .println("event=render_worker_failed type=" + failed.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private RenderWorkerMain() {
    }
}
