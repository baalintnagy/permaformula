package org.permmath.rendering;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import com.google.protobuf.ByteString;
import org.permmath.contract.ComplexityPolicy;
import org.permmath.contract.v1.*;

/** Private, sequential worker protocol; untrusted content never enters executable source. */
public final class RenderWorkerMain {
    public static void main(String[] args) {
        boolean html = "html".equals(args[0]);
        long started = System.nanoTime();
        try (var formulas = html ? null : new AsciiMathRenderer();
                var assets = html ? new RenderAssets() : null;
                var chromium = html ? new ChromiumRenderer(new BrowserSettings(Path.of(args[1]),
                        Duration.ofMillis(Long.parseLong(args[2])), Boolean.parseBoolean(args[3]))) : null) {
            if (formulas != null) formulas.warmUp();
            RenderTimings.elapsed(html ? "html_initialize" : "formula_initialize", started);
            System.out.write(RenderWorkerPool.READY);
            System.out.flush();
            while (true) {
                var request = WorkerMessages.read(System.in, RenderRequest.parser());
                started = System.nanoTime();
                try {
                    RenderLimits.validate(request);
                    if (request.hasHtml() != html) throw new IllegalArgumentException("Wrong worker kind");
                    byte[] svg;
                    if (html) {
                        String url = assets.add(request.getHtml().getDocument());
                        try { svg = chromium.render(url, request.getHtml().getWidth()); }
                        finally { assets.remove(url); }
                    } else {
                        svg = formulas.render(request.getFormula().getFormula()).getBytes(StandardCharsets.UTF_8);
                    }
                    if (svg.length == 0 || svg.length > ComplexityPolicy.CURRENT.get(ComplexityPolicy.Limit.SVG_BYTES)) {
                        throw new IllegalArgumentException("Render output exceeds limit");
                    }
                    System.out.write(RenderWorkerPool.OK);
                    WorkerMessages.write(System.out, RenderResponse.newBuilder().setRequestId(request.getRequestId())
                            .setSvg(ByteString.copyFrom(svg)).build());
                } catch (IllegalArgumentException | RenderException invalid) {
                    System.out.write(RenderWorkerPool.INVALID);
                    System.out.flush();
                    return; // Discard any execution state affected by a failed request.
                } finally {
                    RenderTimings.elapsed(html ? "html_render" : "formula_render", started);
                }
            }
        } catch (Exception failed) {
            System.err.println("event=render_worker_failed type=" + failed.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private RenderWorkerMain() {}
}
