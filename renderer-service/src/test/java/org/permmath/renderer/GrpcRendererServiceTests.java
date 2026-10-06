package org.permmath.renderer;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.inprocess.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.permmath.contract.v1.*;
import org.permmath.rendering.*;

class GrpcRendererServiceTests {

    @Test
    void exchangesMessagesAndMapsOverload() throws Exception {
        try (var fixture = new Fixture((request, budget) -> {
            if (request.getRequestId().equals("busy")) {
                throw new RenderException(RenderException.Kind.OVERLOADED, "busy");
            }
            return RenderResponse.newBuilder().setRequestId(request.getRequestId()).setSvg(ByteString.copyFromUtf8("<svg/>")).build();
        })) {
            var stub = RendererGrpc.newBlockingStub(fixture.channel);
            assertEquals("job", stub.render(RenderRequest.newBuilder().setRequestId("job").build()).getRequestId());
            assertEquals(Status.Code.RESOURCE_EXHAUSTED, assertThrows(StatusRuntimeException.class,
                    () -> stub.render(RenderRequest.newBuilder().setRequestId("busy").build())).getStatus().getCode());
        }
    }

    @Test
    void rpcDeadlineInterruptsExecutingJob() throws Exception {
        var interrupted = new CountDownLatch(1);
        try (var fixture = new Fixture((request, budget) -> {
            try {
                Thread.sleep(60_000);
                throw new AssertionError("Deadline failed");
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw new RenderException(RenderException.Kind.CANCELLED, "cancelled");
            }
        })) {
            assertEquals(Status.Code.DEADLINE_EXCEEDED, assertThrows(StatusRuntimeException.class,
                    () -> RendererGrpc.newBlockingStub(fixture.channel).withDeadlineAfter(1, TimeUnit.SECONDS)
                            .render(RenderRequest.newBuilder().setRequestId("job").build())).getStatus().getCode());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
    }

    static class Fixture implements AutoCloseable {

        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        final Server server;

        final ManagedChannel channel;

        Fixture(RenderJobExecutor jobs) throws Exception {
            var name = InProcessServerBuilder.generateName();
            server = InProcessServerBuilder.forName(name).executor(executor).addService(new GrpcRendererService(jobs, Duration.ofSeconds(10), _ -> {
            })).build().start();
            channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
            executor.close();
        }
    }
}
