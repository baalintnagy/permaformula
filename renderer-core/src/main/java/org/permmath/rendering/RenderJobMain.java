package org.permmath.rendering;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.permmath.contract.v1.RenderRequest;
import org.permmath.contract.v1.RenderResponse;

/** Disposable execution process. Pipes carry standard Protobuf messages, without custom framing. */
public final class RenderJobMain {
    public static void main(String[] args) {
        try {
            var input = CodedInputStream.newInstance(System.in);
            input.setSizeLimit(RenderLimits.MAX_MESSAGE_BYTES);
            var request = RenderRequest.parseFrom(input);
            RenderLimits.validate(request);
            byte[] svg;
            if (request.hasHtml()) {
                var settings = new BrowserSettings(Path.of(args[0]), Duration.ofMillis(Long.parseLong(args[1])),
                        Boolean.parseBoolean(args[2]));
                try (var assets = new RenderAssets(); var chromium = new ChromiumRenderer(settings)) {
                    svg = chromium.render(assets.add(request.getHtml().getDocument()), request.getHtml().getWidth());
                }
            } else {
                try (var renderer = new AsciiMathRenderer()) {
                    svg = renderer.render(request.getFormula().getFormula()).getBytes(StandardCharsets.UTF_8);
                }
            }
            if (svg.length == 0 || svg.length > RenderLimits.MAX_CONTENT_BYTES) {
                throw new java.io.IOException("Render output exceeds size limit");
            }
            RenderResponse.newBuilder().setRequestId(request.getRequestId()).setSvg(ByteString.copyFrom(svg))
                    .build().writeTo(System.out);
            System.out.flush();
        } catch (IllegalArgumentException | RenderException invalid) {
            System.err.println("event=render_job_invalid");
            System.exit(2);
        } catch (Exception failure) {
            System.err.println("event=render_job_failed type=" + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
