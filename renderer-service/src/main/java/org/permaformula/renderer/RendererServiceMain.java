package org.permaformula.renderer;

import static org.permaformula.validation.QuickValidator.validate;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.MultiThreadIoEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollIoHandler;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollServerDomainSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.unix.DomainSocketAddress;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import org.permaformula.contract.v1.RendererServiceGrpc;
import org.permaformula.rendering.BrowserSettings;
import org.permaformula.rendering.RenderLimits;
import org.permaformula.rendering.RenderQueueSettings;
import org.permaformula.rendering.RenderWorkQueue;
import org.permaformula.rendering.SupervisedRenderer;

public final class RendererServiceMain {

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        Duration timeout = Duration
            .ofSeconds(Long.parseLong(env.getOrDefault("RENDERER_TIMEOUT_SECONDS", "30")));
        validate(
            !(timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0),
            () -> new IllegalArgumentException(
                "Renderer timeout must be positive and at most five minutes"));
        BrowserSettings settings = new BrowserSettings(
            Path.of(env.getOrDefault("PLAYWRIGHT_BROWSERS_PATH", ".runtime/browsers")),
            timeout,
            Boolean.parseBoolean(env.getOrDefault("RENDERER_CHROMIUM_SANDBOX", "true")));
        int concurrency = Integer.parseInt(env.getOrDefault("RENDERER_CONCURRENCY", "1"));
        RenderQueueSettings queueSettings = RenderQueueSettings.fromEnvironment(env);
        SupervisedRenderer supervisor = new SupervisedRenderer(settings, concurrency,
            Duration.ofSeconds(
                Long.parseLong(env.getOrDefault("RENDERER_WORKER_STARTUP_SECONDS", "60"))),
            Integer.parseInt(env.getOrDefault("RENDERER_WORKER_MAX_JOBS", "100")));
        RenderWorkQueue jobs = new RenderWorkQueue(concurrency, queueSettings, supervisor);
        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> {
                jobs.close();
                supervisor.close();
            }));
        System.err.println(
            """
            event=renderer_queue_configured capacity=%s configured_bytes=%s effective_bytes=%s \
            wait_seconds=%s\
            """
                .formatted(queueSettings.capacity(),
                    queueSettings.maxRetainedBytes(),
                    jobs.effectiveRetainedBytes(),
                    queueSettings.maxWait()
                        .toSeconds()));
        ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService restarts = Executors
            .newSingleThreadScheduledExecutor(Thread.ofPlatform()
                .daemon(true)
                .factory());
        GrpcRendererService rpc = new GrpcRendererService(jobs,
            timeout.plus(queueSettings.maxWait()),
            failure -> {
                System.err.println("event=renderer_job_failed kind=%s".formatted(failure.kind()));
                // Ordinary job failures retire only their worker. Failed process cleanup is
                // fatal:
                // Docker restart tears down the namespace if native descendants cannot be
                // reaped.
                if (failure.getCause() instanceof IllegalStateException) {
                    jobs.close();
                    restarts.schedule(() -> System.exit(1), 200, TimeUnit.MILLISECONDS);
                }
            });
        NettyServerBuilder builder;
        EventLoopGroup boss = null,
            workers = null;
        Path socket = env.containsKey("RENDERER_SOCKET")
            ? Path.of(env.get("RENDERER_SOCKET"))
            : null;
        if (socket != null) {
            validate(
                Epoll.isAvailable(),
                () -> new IllegalStateException("Unix gRPC transport requires Linux epoll"));
            Files.deleteIfExists(socket);
            boss = new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory());
            workers = new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory());
            builder = NettyServerBuilder.forAddress(new DomainSocketAddress(socket.toString()))
                .channelType(EpollServerDomainSocketChannel.class)
                .bossEventLoopGroup(boss)
                .workerEventLoopGroup(workers);
        } else {
            InetSocketAddress address = new InetSocketAddress(
                env.getOrDefault("RENDERER_BIND_ADDRESS", "127.0.0.1"),
                Integer.parseInt(env.getOrDefault("RENDERER_PORT", "9090")));
            builder = NettyServerBuilder.forAddress(address);
            if (env.containsKey("RENDERER_TLS_CERTIFICATE")) {
                SslContextBuilder tls = GrpcSslContexts.forServer(
                    Path.of(env.get("RENDERER_TLS_CERTIFICATE"))
                        .toFile(),
                    Path.of(env.get("RENDERER_TLS_KEY"))
                        .toFile());
                if (env.containsKey("RENDERER_TLS_CLIENT_CA")) {
                    tls.trustManager(Path.of(env.get("RENDERER_TLS_CLIENT_CA"))
                        .toFile())
                        .clientAuth(ClientAuth.REQUIRE);
                } else {
                    validate(
                        !(!address.getAddress()
                            .isLoopbackAddress()),
                        () -> new IllegalArgumentException(
                            "Non-loopback renderer requires client certificate authentication"));
                }
                builder.sslContext(tls.build());
            } else {
                validate(
                    !(!address.getAddress()
                        .isLoopbackAddress()),
                    () -> new IllegalArgumentException(
                        "Non-loopback renderer requires mutual TLS"));
            }
        }
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus(RendererServiceGrpc.SERVICE_NAME, ServingStatus.SERVING);
        Server server = builder.executor(calls)
            .maxInboundMessageSize(RenderLimits.MAX_MESSAGE_BYTES)
            // Leave room to return overload and serve health instead of queuing in HTTP/2
            // first.
            .maxConcurrentCallsPerConnection(Math.max(8, jobs.maxOutstanding() + 1))
            .addService(rpc)
            .addService(health.getHealthService())
            .build()
            .start();
        if (socket != null) {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw----"));
        }
        EventLoopGroup finalBoss = boss;
        EventLoopGroup finalWorkers = workers;
        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> {
                health.enterTerminalState();
                server.shutdownNow();
                jobs.close();
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
        System.out.println("event=renderer_ready transport=%s".formatted(
            (socket == null ? "tcp" : "unix")));
        System.out.flush();
        server.awaitTermination();
    }
}
