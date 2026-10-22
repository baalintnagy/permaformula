package org.permaformula.renderer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.jupiter.api.Test;
import org.permaformula.contract.v1.AsciiMathFormula;
import org.permaformula.contract.v1.RenderRequest;
import org.permaformula.contract.v1.RenderResponse;
import org.permaformula.contract.v1.RendererServiceGrpc;
import org.permaformula.rendering.RenderException;
import org.permaformula.rendering.RenderJobExecutor;
import org.permaformula.rendering.RenderQueueSettings;
import org.permaformula.rendering.RenderWorkQueue;

class GrpcRendererServiceTests {

    @Test
    void queueExpiryDoesNotRestartOrInterruptAnExecutingRpc() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var restarts = new AtomicInteger();
        try (var queue = new RenderWorkQueue(1,
            new RenderQueueSettings(1, 1_000_000, Duration.ofMillis(150)),
            (request, _) -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException _) {
                    throw new RenderException(RenderException.Kind.CANCELLED,
                        "active interrupted");
                }
                return RenderResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setSvg(ByteString.copyFromUtf8("<svg/>"))
                    .build();
            });
            var fixture = new Fixture(queue, _ -> restarts.incrementAndGet());
            var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var stub = RendererServiceGrpc.newBlockingStub(fixture.channel);
            var active = callers.submit(() -> stub.render(validRequest("active")));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(Status.Code.DEADLINE_EXCEEDED,
                    assertThrows(StatusRuntimeException.class,
                        () -> stub.render(validRequest("expired")))
                        .getStatus()
                        .getCode());
                assertEquals(0, restarts.get());
                assertFalse(active.isDone());
                release.countDown();
                assertEquals("active", active.get(2, TimeUnit.SECONDS)
                    .getRequestId());
                assertEquals("next", stub.render(validRequest("next"))
                    .getRequestId());
                assertEquals(0, restarts.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rpcCancellationWhileQueuedDoesNotExecuteOrRestart() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var restarts = new AtomicInteger();
        var executions = new AtomicInteger();
        try (var queue = new RenderWorkQueue(1,
            new RenderQueueSettings(1, 1_000_000, Duration.ofSeconds(2)),
            (request, _) -> {
                executions.incrementAndGet();
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException _) {
                    throw new RenderException(RenderException.Kind.CANCELLED,
                        "active interrupted");
                }
                return RenderResponse.newBuilder()
                    .setRequestId(request.getRequestId())
                    .setSvg(ByteString.copyFromUtf8("<svg/>"))
                    .build();
            });
            var fixture = new Fixture(queue, _ -> restarts.incrementAndGet());
            var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var stub = RendererServiceGrpc.newBlockingStub(fixture.channel);
            var active = callers.submit(() -> stub.render(validRequest("active")));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(Status.Code.DEADLINE_EXCEEDED,
                    assertThrows(StatusRuntimeException.class,
                        () -> stub.withDeadlineAfter(100, TimeUnit.MILLISECONDS)
                            .render(validRequest("cancelled")))
                        .getStatus()
                        .getCode());
                assertFalse(active.isDone());
                release.countDown();
                active.get(2, TimeUnit.SECONDS);
                assertEquals("next", stub.render(validRequest("next"))
                    .getRequestId());
                assertEquals(2, executions.get());
                assertEquals(0, restarts.get());
            } finally {
                release.countDown();
            }
        }
    }

    static RenderRequest validRequest(String id) {
        return RenderRequest.newBuilder()
            .setRequestId(id)
            .setFormula(AsciiMathFormula.newBuilder()
                .setFormula("x"))
            .build();
    }

    @Test
    void exchangesMessagesAndMapsOverload() throws Exception {
        try (var fixture = new Fixture((request, _) -> {
            if (request.getRequestId()
                .equals("busy")) {
                throw new RenderException(RenderException.Kind.OVERLOADED, "busy");
            }
            return RenderResponse.newBuilder()
                .setRequestId(request.getRequestId())
                .setSvg(ByteString.copyFromUtf8("<svg/>"))
                .build();
        })) {
            var stub = RendererServiceGrpc.newBlockingStub(fixture.channel);
            assertEquals("job",
                stub.render(RenderRequest.newBuilder()
                    .setRequestId("job")
                    .build())
                    .getRequestId());
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, assertThrows(StatusRuntimeException.class,
                () -> stub.render(RenderRequest.newBuilder()
                    .setRequestId("busy")
                    .build()))
                .getStatus()
                .getCode());
        }
    }

    @Test
    void rpcDeadlineInterruptsExecutingJob() throws Exception {
        var interrupted = new CountDownLatch(1);
        try (var fixture = new Fixture((_, _) -> {
            try {
                Thread.sleep(60_000);
                throw new AssertionError("Deadline failed");
            } catch (InterruptedException _) {
                interrupted.countDown();
                throw new RenderException(RenderException.Kind.CANCELLED, "cancelled");
            }
        })) {
            assertEquals(Status.Code.DEADLINE_EXCEEDED, assertThrows(StatusRuntimeException.class,
                () -> RendererServiceGrpc.newBlockingStub(fixture.channel)
                    .withDeadlineAfter(1, TimeUnit.SECONDS)
                    .render(RenderRequest.newBuilder()
                        .setRequestId("job")
                        .build()))
                .getStatus()
                .getCode());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
    }

    static class Fixture implements AutoCloseable {

        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        final Server server;

        final ManagedChannel channel;

        Fixture(RenderJobExecutor jobs) throws Exception {
            this(jobs, _ -> {
            });
        }

        Fixture(RenderJobExecutor jobs, Consumer<RenderException> failedJob) throws Exception {
            var name = InProcessServerBuilder.generateName();
            server = InProcessServerBuilder.forName(name)
                .executor(executor)
                .addService(new GrpcRendererService(jobs, Duration.ofSeconds(10), failedJob))
                .build()
                .start();
            channel = InProcessChannelBuilder.forName(name)
                .directExecutor()
                .build();
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
            executor.close();
        }
    }
}
