package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.permmath.contract.v1.*;
import org.springframework.web.server.ResponseStatusException;

class GrpcRenderGatewayTests {

    @Test
    void grpcReceivesRemainingRequestBudgetRatherThanFullRendererTimeout() throws Exception {
        var server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(new RendererGrpc.RendererImplBase() {
                    @Override public void render(RenderRequest request, StreamObserver<RenderResponse> observer) {
                        long remaining = Context.current().getDeadline().timeRemaining(java.util.concurrent.TimeUnit.MILLISECONDS);
                        observer.onNext(RenderResponse.newBuilder().setRequestId(request.getRequestId())
                                .setSvg(ByteString.copyFromUtf8(Long.toString(remaining))).build());
                        observer.onCompleted();
                    }
                }).build().start();
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try (var gateway = new GrpcRenderGateway(channel, Duration.ofSeconds(5));
                var scope = WorkDeadline.open(Duration.ofSeconds(2))) {
            long remaining = Long.parseLong(new String(gateway.renderFormula("x"), java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(remaining > 0 && remaining <= 2000, "RPC must use remaining API budget");
        } finally { server.shutdownNow(); }
    }

    @Test
    void actualGrpcTransportPreservesContentAndMapsErrors() throws Exception {
        var server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(new RendererGrpc.RendererImplBase() {

            public void render(RenderRequest request, StreamObserver<RenderResponse> observer) {
                var input = request.hasHtml() ? request.getHtml().getDocument() : request.getFormula().getFormula();
                if (input.equals("busy")) {
                    observer.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException());
                    return;
                }
                if (request.hasHtml()) {
                    assertEquals(240, request.getHtml().getWidth());
                }
                        observer.onNext(RenderResponse.newBuilder().setRequestId(input.equals("wrong") ? "wrong" : request.getRequestId())
                                .setSvg(ByteString.copyFromUtf8(input)).build());
                observer.onCompleted();
            }
        }).build().start();
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
        try (var gateway = new GrpcRenderGateway(channel, Duration.ofSeconds(5))) {
            assertEquals("<p>π</p>", new String(gateway.renderHtml("<p>π</p>", 240), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("sqrt(x)", new String(gateway.renderFormula("sqrt(x)"), java.nio.charset.StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> gateway.renderFormula("wrong"));
            assertEquals(503, assertThrows(ResponseStatusException.class, () -> gateway.renderFormula("busy")).getStatusCode().value());
        } finally {
            server.shutdownNow();
        }
    }
}
