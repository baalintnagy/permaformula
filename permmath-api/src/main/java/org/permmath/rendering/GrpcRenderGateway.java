package org.permmath.rendering;

import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.MultiThreadIoEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.*;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.permmath.contract.RenderProtocol;
import org.permmath.contract.v1.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;

@Service
public final class GrpcRenderGateway implements RenderGateway, AutoCloseable {

    private final ManagedChannel channel;

    private final EventLoopGroup events;

    private final Duration timeout;

    @Autowired
    public GrpcRenderGateway(RenderingProperties properties) throws IOException {
        timeout = properties.timeout();
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Render timeout must be positive");
        }
        NettyChannelBuilder builder;
        if (properties.socket() != null) {
            if (!Epoll.isAvailable()) {
                throw new IOException("Unix gRPC transport requires Linux epoll");
            }
            events = new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory());
            builder = NettyChannelBuilder.forAddress(new DomainSocketAddress(properties.socket().toString()))
                    .channelType(EpollDomainSocketChannel.class).eventLoopGroup(events).usePlaintext();
        } else {
            events = null;
            builder = NettyChannelBuilder.forTarget(properties.target());
            if (properties.plaintext()) {
                builder.usePlaintext();
            } else {
                var tls = GrpcSslContexts.forClient();
                if (properties.tlsCa() != null) {
                    tls.trustManager(properties.tlsCa().toFile());
                }
                if ((properties.tlsCertificate() == null) != (properties.tlsKey() == null)) {
                    throw new IllegalArgumentException("Both renderer TLS certificate and key are required");
                }
                if (properties.tlsCertificate() != null) {
                    tls.keyManager(properties.tlsCertificate().toFile(), properties.tlsKey().toFile());
                }
                builder.sslContext(tls.build());
            }
        }
        channel = builder.maxInboundMessageSize(RenderProtocol.MAX_MESSAGE_BYTES).build();
    }

    GrpcRenderGateway(ManagedChannel channel, Duration timeout) {
        this.channel = channel;
        this.timeout = timeout;
        events = null;
    }

    @Override
    public byte[] renderHtml(String document, int width) throws IOException {
        return render(RenderRequest.newBuilder().setHtml(HtmlDocument.newBuilder().setDocument(document).setWidth(width)));
    }

    @Override
    public byte[] renderFormula(String formula) throws IOException {
        return render(RenderRequest.newBuilder().setFormula(AsciiMathFormula.newBuilder().setFormula(formula)));
    }

    private byte[] render(RenderRequest.Builder builder) throws IOException {
        var request = builder.setRequestId(UUID.randomUUID().toString()).build();
        int contentSize = request.hasHtml() ? request.getHtml().getDocumentBytes().size()
                : request.getFormula().getFormulaBytes().size();
        if (contentSize > RenderProtocol.MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("Rendering content is too large");
        }
        try {
            var response = RendererGrpc.newBlockingStub(channel).withDeadlineAfter(WorkDeadline.remainingNanos(timeout), TimeUnit.NANOSECONDS)
                    .render(request);
            if (!request.getRequestId().equals(response.getRequestId()) || response.getSvg().isEmpty()
                    || response.getSvg().size() > RenderProtocol.MAX_CONTENT_BYTES) {
                throw new IOException("Invalid renderer response");
            }
            return response.getSvg().toByteArray();
        } catch (StatusRuntimeException failure) {
            switch(failure.getStatus().getCode()) {
                case INVALID_ARGUMENT ->
                    throw new IllegalArgumentException("Invalid rendering content", failure);
                case DEADLINE_EXCEEDED ->
                    throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Renderer deadline exceeded", failure);
                case RESOURCE_EXHAUSTED, UNAVAILABLE ->
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Renderer is busy or unavailable", failure);
                default ->
                    throw new IOException("Renderer request failed", failure);
            }
        }
    }

    @PreDestroy
    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (events != null) {
                events.shutdownGracefully();
            }
        }
    }
}
