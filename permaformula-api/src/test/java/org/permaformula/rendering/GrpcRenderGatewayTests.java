package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.google.protobuf.ByteString;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.stub.StreamObserver;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.permaformula.contract.v1.*;
import org.springframework.web.server.ResponseStatusException;

class GrpcRenderGatewayTests {

    @Test
    void grpcReceivesRemainingRequestBudgetRatherThanFullRendererTimeout() throws Exception {
        var server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(new RendererGrpc.RendererImplBase() {
                @Override
                public void render(RenderRequest request, StreamObserver<RenderResponse> observer) {
                    long remaining = Context.current().getDeadline()
                        .timeRemaining(TimeUnit.MILLISECONDS);
                    observer.onNext(RenderResponse.newBuilder().setRequestId(request.getRequestId())
                        .setSvg(ByteString.copyFromUtf8(Long.toString(remaining))).build());
                    observer.onCompleted();
                }
            }).build().start();
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext()
            .build();
        try (var gateway = new GrpcRenderGateway(channel, Duration.ofSeconds(5));
            var _ = WorkDeadline.open(Duration.ofSeconds(2))) {
            long remaining = Long
                .parseLong(new String(gateway.renderFormula("x"), StandardCharsets.UTF_8));
            assertTrue(remaining > 0
                && remaining <= 2000, "RPC must use remaining API budget");
        } finally {
            server.shutdownNow();
        }
    }

    @Test
    void actualGrpcTransportPreservesContentAndMapsErrors() throws Exception {
        var server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .addService(new RendererGrpc.RendererImplBase() {

                public void render(RenderRequest request, StreamObserver<RenderResponse> observer) {
                    var input = request.hasHtml() ? request.getHtml().getDocument()
                        : request.hasJpegSvg() ? request.getJpegSvg()
                            : request.getFormula().getFormula();
                    if (input.equals("busy")) {
                        observer.onError(Status.RESOURCE_EXHAUSTED.asRuntimeException());
                        return;
                    }
                    if (input.equals("invalid")) {
                        observer.onError(Status.INVALID_ARGUMENT.withDescription("Private details")
                            .asRuntimeException());
                        return;
                    }
                    if (request.hasHtml()) {
                        assertEquals(240, request.getHtml().getWidth());
                    }
                    observer.onNext(RenderResponse.newBuilder()
                        .setRequestId(input.equals("wrong") ? "wrong" : request.getRequestId())
                        .setSvg(ByteString.copyFromUtf8(input)).build());
                    observer.onCompleted();
                }
            }).build().start();
        var channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext()
            .build();
        try (var gateway = new GrpcRenderGateway(channel, Duration.ofSeconds(5))) {
            assertEquals("<p>π</p>",
                new String(gateway.renderHtml("<p>π</p>", 240), StandardCharsets.UTF_8));
            assertEquals("<svg>π</svg>",
                new String(gateway.renderJpeg("<svg>π</svg>"), StandardCharsets.UTF_8));
            assertEquals("sqrt(x)",
                new String(gateway.renderFormula("sqrt(x)"), StandardCharsets.UTF_8));
            assertThrows(IOException.class, () -> gateway.renderFormula("wrong"));
            var invalid = assertThrows(ConstraintViolationException.class,
                () -> gateway.renderFormula("invalid"));
            assertEquals("Invalid rendering content", invalid.getMessage());
            var overloaded = assertThrows(ResponseStatusException.class,
                () -> gateway.renderFormula("busy"));
            assertEquals(503, overloaded.getStatusCode().value());
            assertEquals("1", overloaded.getHeaders().getFirst("Retry-After"));
            assertEquals("no-store", overloaded.getHeaders().getCacheControl());
        } finally {
            server.shutdownNow();
        }
    }
}
