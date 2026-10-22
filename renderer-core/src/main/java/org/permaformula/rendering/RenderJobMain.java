package org.permaformula.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.contract.v1.RenderRequest;
import org.permaformula.contract.v1.RenderResponse;

/** Disposable execution process. Pipes carry standard Protobuf messages, without custom framing. */
public final class RenderJobMain {
    public static void main(String[] args) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(System.in);
            input.setSizeLimit(RenderLimits.MAX_MESSAGE_BYTES);
            RenderRequest request = RenderRequest.parseFrom(input);
            byte[] svg;
            if (request.hasHtml()
                || request.hasJpegSvg()) {
                BrowserSettings settings = new BrowserSettings(Path.of(args[0]),
                    Duration.ofMillis(Long.parseLong(args[1])),
                    Boolean.parseBoolean(args[2]));
                try (RenderAssets assets = new RenderAssets();
                    ChromiumRenderer chromium = new ChromiumRenderer(settings)) {
                    svg = request.hasJpegSvg()
                        ? chromium.jpeg(assets.add("<!doctype html><html><body></body></html>"),
                            request.getJpegSvg())
                        : chromium.render(assets.add(request.getHtml()
                            .getDocument()),
                            request.getHtml()
                                .getWidth());
                }
            } else {
                try (AsciiMathRenderer renderer = new AsciiMathRenderer()) {
                    svg = renderer.render(request.getFormula()
                        .getFormula())
                        .getBytes(StandardCharsets.UTF_8);
                }
            }
            if (svg.length == 0
                || svg.length > ComplexityPolicy.CURRENT.get(ComplexityPolicy.Limit.SVG_BYTES)) {
                throw new IOException("Render output exceeds size limit");
            }
            RenderResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setSvg(ByteString.copyFrom(svg))
                .build()
                .writeTo(System.out);
            System.out.flush();
        } catch (IllegalArgumentException _) {
            System.err.println("event=render_job_invalid");
            System.exit(2);
        } catch (Exception failure) {
            System.err.println("event=render_job_failed type=%s".formatted(
                failure.getClass()
                    .getSimpleName()));
            System.exit(1);
        }
    }
}
