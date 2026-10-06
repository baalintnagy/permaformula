package org.permmath.rendering;

import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import org.permmath.contract.v1.RenderRequest;
import org.permmath.contract.v1.RenderResponse;

/**
 * Admission and process deadlines are independent of the RPC transport and browser page.
 */
public final class SupervisedRenderer implements RenderJobExecutor {

    private final BrowserSettings settings;

    private final Semaphore permits;

    public SupervisedRenderer(BrowserSettings settings, int concurrency) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("Renderer concurrency must be positive");
        }
        this.settings = settings;
        permits = new Semaphore(concurrency);
    }

    @Override
    public RenderResponse execute(RenderRequest request, Duration budget) throws RenderException {
        RenderLimits.validate(request);
        if (!permits.tryAcquire()) {
            throw new RenderException(RenderException.Kind.OVERLOADED, "Renderer is busy");
        }
        try {
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-Xmx512m", "-XX:+ExitOnOutOfMemoryError",
                    "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                    "-cp", System.getProperty("java.class.path"), RenderJobMain.class.getName(),
                    settings.browserDirectory().toAbsolutePath().toString(), Long.toString(budget.toMillis()),
                    Boolean.toString(settings.chromiumSandbox()));
            process.environment().clear();
            process.environment().putAll(java.util.Map.of("HOME", System.getProperty("java.io.tmpdir"),
                    "TMPDIR", System.getProperty("java.io.tmpdir"), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
            process.redirectError(ProcessBuilder.Redirect.INHERIT);
            return run(process, request, budget);
        } finally {
            permits.release();
        }
    }

    static RenderResponse run(ProcessBuilder builder, RenderRequest request, Duration budget) throws RenderException {
        long deadline = System.nanoTime() + budget.toNanos();
        Process process;
        try {
            process = builder.start();
        } catch (IOException failure) {
            throw new RenderException(RenderException.Kind.FAILED, "Render process failed", failure);
        }
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = tasks.submit(() -> {
                var input = CodedInputStream.newInstance(process.getInputStream());
                input.setSizeLimit(RenderLimits.MAX_MESSAGE_BYTES);
                return RenderResponse.parseFrom(input);
            });
            var input = tasks.submit(() -> {
                try (var stream = process.getOutputStream()) {
                    request.writeTo(stream);
                }
                return null;
            });
            try {
                input.get(remaining(deadline), TimeUnit.NANOSECONDS);
                var response = output.get(remaining(deadline), TimeUnit.NANOSECONDS);
                if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                    throw new TimeoutException();
                }
                if (process.exitValue() == 2) {
                    throw new RenderException(RenderException.Kind.INVALID, "Invalid rendering content");
                }
                if (process.exitValue() != 0 || !request.getRequestId().equals(response.getRequestId())
                        || response.getSvg().isEmpty() || response.getSvg().size() > RenderLimits.MAX_CONTENT_BYTES) {
                    throw new RenderException(RenderException.Kind.FAILED, "Invalid render process result");
                }
                return response;
            } catch (TimeoutException expired) {
                throw new RenderException(RenderException.Kind.DEADLINE, "Render deadline exceeded", expired);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new RenderException(RenderException.Kind.CANCELLED, "Rendering cancelled", cancelled);
            } catch (ExecutionException failed) {
                throw new RenderException(RenderException.Kind.FAILED, "Render process failed", failed);
            } finally {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                    process.getOutputStream().close();
                } catch (IOException ignored) {
                    /* The process has already been terminated. */
                }
            }
        }
    }

    private static long remaining(long deadline) throws TimeoutException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException();
        }
        return remaining;
    }
}
