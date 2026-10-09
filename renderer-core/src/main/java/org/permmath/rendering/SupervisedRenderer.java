package org.permmath.rendering;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

import org.permmath.contract.ComplexityPolicy;
import org.permmath.contract.v1.RenderRequest;
import org.permmath.contract.v1.RenderResponse;

import com.google.protobuf.CodedInputStream;

/**
 * Admission and process deadlines are independent of the RPC transport and browser page.
 */
public final class SupervisedRenderer implements RenderJobExecutor, AutoCloseable {

    private final BrowserSettings settings;

    private final Semaphore permits;
    private final RenderWorkerPool formulas;
    private final RenderWorkerPool html;

    public SupervisedRenderer(BrowserSettings settings, int concurrency) {
        this(settings, concurrency, Duration.ofSeconds(60), 100);
    }

    public SupervisedRenderer(BrowserSettings settings, int concurrency, Duration startupTimeout, int maxJobs) {
        if (concurrency < 1 || concurrency > 64 || startupTimeout.isNegative() || startupTimeout.isZero()
                || startupTimeout.compareTo(Duration.ofMinutes(5)) > 0 || maxJobs < 1) {
            throw new IllegalArgumentException("Invalid renderer worker settings");
        }
        this.settings = settings;
        permits = new Semaphore(concurrency);
        formulas = new RenderWorkerPool(concurrency, () -> worker("formula"), startupTimeout, maxJobs);
        html = new RenderWorkerPool(concurrency, () -> worker("html"), startupTimeout, maxJobs);
    }

    @Override
    public RenderResponse execute(RenderRequest request, Duration budget) throws RenderException {
        RenderLimits.validate(request);
        budget = budget.compareTo(settings.timeout()) > 0 ? settings.timeout() : budget;
        if (budget.isZero() || budget.isNegative()) {
            throw new RenderException(RenderException.Kind.DEADLINE, "Render deadline exceeded");
        }
        if (!permits.tryAcquire()) {
            throw new RenderException(RenderException.Kind.OVERLOADED, "Renderer is busy");
        }
        try {
            return (request.hasHtml() || request.hasJpegSvg() ? html : formulas).execute(request, budget);
        } finally {
            permits.release();
        }
    }

    private ProcessBuilder worker(String kind) {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--enable-native-access=ALL-UNNAMED", "-Xmx1024m", "-XX:+ExitOnOutOfMemoryError",
                    "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                    "-cp", System.getProperty("java.class.path"), RenderWorkerMain.class.getName(), kind,
                    settings.browserDirectory().toAbsolutePath().toString(), Long.toString(settings.timeout().toMillis()),
                    Boolean.toString(settings.chromiumSandbox()));
        process.environment().clear();
        process.environment().putAll(ComplexityPolicy.CURRENT.workerEnvironment());
        process.environment().putAll(Map.of("HOME", System.getProperty("java.io.tmpdir"),
                    "TMPDIR", System.getProperty("java.io.tmpdir"), "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));
        process.redirectError(ProcessBuilder.Redirect.INHERIT);
        return process;
    }

    @Override public void close() {
        try { formulas.close(); }
        finally { html.close(); }
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
                        || response.getSvg().isEmpty() || response.getSvg().size() > ComplexityPolicy.CURRENT
                                .get(ComplexityPolicy.Limit.SVG_BYTES)) {
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
