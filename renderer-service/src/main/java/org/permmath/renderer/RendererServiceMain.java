package org.permmath.renderer;

import io.grpc.netty.shaded.io.grpc.netty.*;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.MultiThreadIoEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.*;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.*;
import org.permmath.contract.v1.RendererGrpc;
import org.permmath.rendering.*;

public final class RendererServiceMain {

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        var timeout = Duration.ofSeconds(Long.parseLong(env.getOrDefault("RENDERER_TIMEOUT_SECONDS", "30")));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Renderer timeout must be positive");
        }
        var settings = new BrowserSettings(Path.of(env.getOrDefault("PLAYWRIGHT_BROWSERS_PATH", ".runtime/browsers")),
                timeout, Boolean.parseBoolean(env.getOrDefault("RENDERER_CHROMIUM_SANDBOX", "true")));
        var jobs = new SupervisedRenderer(settings, Integer.parseInt(env.getOrDefault("RENDERER_CONCURRENCY", "1")));
        var calls = Executors.newVirtualThreadPerTaskExecutor();
        var restarts = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).factory());
        var rpc = new GrpcRendererService(jobs, timeout, failure -> {
            System.err.println("event=renderer_job_failed kind=" + failure.kind());
            // Docker restart tears down the PID namespace, including orphan native processes.
            restarts.schedule(() -> System.exit(1), 200, TimeUnit.MILLISECONDS);
        });
        NettyServerBuilder builder;
        EventLoopGroup boss = null, workers = null;
        Path socket = env.containsKey("RENDERER_SOCKET") ? Path.of(env.get("RENDERER_SOCKET")) : null;
        if (socket != null) {
            if (!Epoll.isAvailable()) {
                throw new IllegalStateException("Unix gRPC transport requires Linux epoll");
            }
            Files.deleteIfExists(socket);
            boss = new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory());
            workers = new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory());
            builder = NettyServerBuilder.forAddress(new DomainSocketAddress(socket.toString()))
                    .channelType(EpollServerDomainSocketChannel.class).bossEventLoopGroup(boss).workerEventLoopGroup(workers);
        } else {
            var address = new InetSocketAddress(env.getOrDefault("RENDERER_BIND_ADDRESS", "127.0.0.1"),
                    Integer.parseInt(env.getOrDefault("RENDERER_PORT", "9090")));
            builder = NettyServerBuilder.forAddress(address);
            if (env.containsKey("RENDERER_TLS_CERTIFICATE")) {
                var tls = GrpcSslContexts.forServer(Path.of(env.get("RENDERER_TLS_CERTIFICATE")).toFile(),
                        Path.of(env.get("RENDERER_TLS_KEY")).toFile());
                if (env.containsKey("RENDERER_TLS_CLIENT_CA")) {
                    tls.trustManager(Path.of(env.get("RENDERER_TLS_CLIENT_CA")).toFile()).clientAuth(ClientAuth.REQUIRE);
                } else if (!address.getAddress().isLoopbackAddress()) {
                    throw new IllegalArgumentException("Non-loopback renderer requires client certificate authentication");
                }
                builder.sslContext(tls.build());
            } else if (!address.getAddress().isLoopbackAddress()) {
                throw new IllegalArgumentException("Non-loopback renderer requires mutual TLS");
            }
        }
        var health = new HealthStatusManager();
        health.setStatus(RendererGrpc.SERVICE_NAME, ServingStatus.SERVING);
        var server = builder.executor(calls).maxInboundMessageSize(RenderLimits.MAX_MESSAGE_BYTES)
                .maxConcurrentCallsPerConnection(8).addService(rpc).addService(health.getHealthService()).build().start();
        if (socket != null) {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw----"));
        }
        var finalBoss = boss;
        var finalWorkers = workers;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            health.enterTerminalState();
            server.shutdownNow();
            calls.shutdownNow();
            restarts.shutdownNow();
            if (finalBoss != null) {
                finalBoss.shutdownGracefully();
            }
            if (finalWorkers != null) {
                finalWorkers.shutdownGracefully();
            }
        }));
        // Eclipse launch groups observe stdout for their readiness expression.
        System.out.println("event=renderer_ready transport=" + (socket == null ? "tcp" : "unix"));
        System.out.flush();
        server.awaitTermination();
    }
}
