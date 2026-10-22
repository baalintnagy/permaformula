package org.permaformula.rendering;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.permaformula.contract.v1.RenderRequest;
import org.permaformula.contract.v1.RenderResponse;

class RenderWorkerPoolTests {
    @TempDir
    Path temp;

    private static RenderRequest request(String mode) {
        return SupervisedRendererTests.request()
            .toBuilder()
            .setRequestId(mode)
            .build();
    }

    private ProcessBuilder child(String mode) {
        return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java")
                .toString(),
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Child.class.getName(),
            mode, temp.resolve("pid")
                .toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    private static long pid(RenderResponse response) {
        return Long.parseLong(response.getSvg()
            .toStringUtf8());
    }

    @Test
    void prestartsReusesAndRecyclesWorkers() throws Exception {
        try (var pool = new RenderWorkerPool(1, () -> child("ok"), Duration.ofSeconds(10), 2)) {
            await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(temp.resolve("pid")));
            long first = pid(pool.execute(request("one"), Duration.ofSeconds(10)));
            assertEquals(first, pid(pool.execute(request("two"), Duration.ofSeconds(10))));
            assertFalse(ProcessHandle.of(first)
                .map(ProcessHandle::isAlive)
                .orElse(false));
            assertNotEquals(first, pid(pool.execute(request("three"), Duration.ofSeconds(10))));
        }
    }

    @Test
    void discardsTimedOutInvalidAndMalformedWorkersAndRecovers() throws Exception {
        try (var pool = new RenderWorkerPool(1, () -> child("ok"), Duration.ofSeconds(10), 100)) {
            for (String mode : new String[] { "hang", "wrong-id", "invalid", "oversized",
                    "crash" }) {
                long oldPid = pid(pool.execute(request("ok"), Duration.ofSeconds(10)));
                var failure = assertThrows(RenderException.class,
                    () -> pool.execute(request(mode), Duration.ofMillis(500)));
                assertEquals(mode.equals("hang") ? RenderException.Kind.DEADLINE
                    : mode.equals("invalid") ? RenderException.Kind.INVALID
                        : RenderException.Kind.FAILED,
                    failure.kind());
                assertFalse(ProcessHandle.of(oldPid)
                    .map(ProcessHandle::isAlive)
                    .orElse(false));
                assertNotEquals(oldPid, pid(pool.execute(request("ok"), Duration.ofSeconds(10))));
            }
        }
    }

    @Test
    void cancelsRunningWorkerAndClosesIdleWorkers() throws Exception {
        long workerPid;
        try (var pool = new RenderWorkerPool(1, () -> child("ok"), Duration.ofSeconds(10), 100);
            var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            workerPid = pid(pool.execute(request("ok"), Duration.ofSeconds(10)));
            var hanging = callers
                .submit(() -> pool.execute(request("hang"), Duration.ofSeconds(30)));
            await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> Files.exists(temp.resolve("pid.hang")));
            hanging.cancel(true);
            long oldPid = workerPid;
            await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> !ProcessHandle.of(oldPid)
                    .map(ProcessHandle::isAlive)
                    .orElse(false));
            workerPid = pid(pool.execute(request("ok"), Duration.ofSeconds(10)));
        }
        assertFalse(ProcessHandle.of(workerPid)
            .map(ProcessHandle::isAlive)
            .orElse(false));
    }

    @Test
    void deadlineDuringInitializationKillsThatProcessWithoutWaitingForStartupTimeout()
        throws Exception {
        var starts = new AtomicInteger();
        try (var pool = new RenderWorkerPool(1,
            () -> child(starts.getAndIncrement() == 0 ? "slow" : "ok"),
            Duration.ofSeconds(30),
            100)) {
            await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> Files.exists(temp.resolve("pid")));
            long oldPid = Long.parseLong(Files.readString(temp.resolve("pid")));
            long started = System.nanoTime();
            assertEquals(RenderException.Kind.DEADLINE, assertThrows(RenderException.class,
                () -> pool.execute(request("ok"), Duration.ofMillis(100)))
                .kind());
            assertTrue(
                Duration.ofNanos(System.nanoTime() - started)
                    .compareTo(Duration.ofSeconds(5)) < 0);
            assertFalse(ProcessHandle.of(oldPid)
                .map(ProcessHandle::isAlive)
                .orElse(false));
            assertNotEquals(oldPid, pid(pool.execute(request("ok"), Duration.ofSeconds(10))));
        }
    }

    @Test
    void rejectsOversizedFramesBeforeAllocation() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var coded = CodedOutputStream.newInstance(bytes);
        coded.writeUInt32NoTag(RenderLimits.MAX_MESSAGE_BYTES + 1);
        coded.flush();
        assertThrows(IOException.class, () -> WorkerMessages
            .read(new ByteArrayInputStream(bytes.toByteArray()), RenderRequest.parser()));
    }

    @Test
    void startupTimeoutReplacesTheFailedInitializer() throws Exception {
        var starts = new AtomicInteger();
        try (var pool = new RenderWorkerPool(1,
            () -> child(starts.getAndIncrement() == 0 ? "slow" : "ok"),
            Duration.ofSeconds(1),
            100)) {
            assertEquals(RenderException.Kind.FAILED, assertThrows(RenderException.class,
                () -> pool.execute(request("ok"), Duration.ofSeconds(10)))
                .kind());
            assertTrue(pid(pool.execute(request("ok"), Duration.ofSeconds(10))) > 0);
        }
    }

    @Test
    void crashReapsNativeDescendantsEvenAfterTheWorkerHasExited() throws Exception {
        try (var pool = new RenderWorkerPool(1, () -> child("descendant"), Duration.ofSeconds(10),
            100)) {
            pool.execute(request("ok"), Duration.ofSeconds(10));
            long descendant = Long.parseLong(Files.readString(temp.resolve("pid.descendant")));
            assertTrue(ProcessHandle.of(descendant)
                .map(ProcessHandle::isAlive)
                .orElse(false));
            assertEquals(RenderException.Kind.FAILED, assertThrows(RenderException.class,
                () -> pool.execute(request("crash"), Duration.ofSeconds(10)))
                .kind());
            assertFalse(ProcessHandle.of(descendant)
                .map(ProcessHandle::isAlive)
                .orElse(false));
        }
    }

    @Test
    void independentSlotsRenderConcurrentlyWithoutSharingAProcess() throws Exception {
        try (var pool = new RenderWorkerPool(2, () -> child("ok"), Duration.ofSeconds(10), 100);
            var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers
                .submit(() -> pool.execute(request("pause"), Duration.ofSeconds(10)));
            var second = callers
                .submit(() -> pool.execute(request("pause"), Duration.ofSeconds(10)));
            assertNotEquals(pid(first.get(10, TimeUnit.SECONDS)),
                pid(second.get(10, TimeUnit.SECONDS)));
        }
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            var pidFile = Path.of(args[1]);
            var pending = pidFile.resolveSibling("pid.tmp.%s".formatted(
                ProcessHandle.current()
                    .pid()));
            Files.writeString(pending, Long.toString(ProcessHandle.current()
                .pid()));
            Files.move(pending, pidFile, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            if (args[0]
                .equals("slow")) {
                Thread.sleep(60_000);
            }
            if (args[0]
                .equals("descendant")) {
                var descendant = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java")
                        .toString(),
                    "-cp", System.getProperty("java.class.path"),
                    Descendant.class.getName())
                    .start();
                Files.writeString(pidFile.resolveSibling("pid.descendant"),
                    Long.toString(descendant.pid()));
            }
            System.out.write(RenderWorkerPool.READY);
            System.out.flush();
            while (true) {
                var request = WorkerMessages.read(System.in, RenderRequest.parser());
                String mode = request.getRequestId();
                if (mode.equals("pause")) {
                    Files.writeString(
                        pidFile.resolveSibling("pid.pause.%s".formatted(
                            ProcessHandle.current()
                                .pid())),
                        "ready");
                    while (true) {
                        try (var peers = Files.list(pidFile.getParent())) {
                            if (peers.filter(
                                path -> path.getFileName()
                                    .toString()
                                    .startsWith("pid.pause."))
                                .count() == 2) {
                                break;
                            }
                        }
                        Thread.sleep(10);
                    }
                }
                if (mode.equals("hang")) {
                    Files.writeString(pidFile.resolveSibling("pid.hang"), "ready");
                    Thread.sleep(60_000);
                }
                if (mode.equals("crash")) {
                    System.exit(1);
                }
                if (mode.equals("invalid")) {
                    System.out.write(RenderWorkerPool.INVALID);
                    System.out.flush();
                    continue;
                }
                System.out.write(RenderWorkerPool.OK);
                if (mode.equals("oversized")) {
                    var coded = CodedOutputStream.newInstance(System.out);
                    coded.writeUInt32NoTag(RenderLimits.MAX_MESSAGE_BYTES + 1);
                    coded.flush();
                    continue;
                }
                WorkerMessages.write(System.out,
                    RenderResponse.newBuilder()
                        .setRequestId(mode.equals("wrong-id") ? "wrong" : mode)
                        .setSvg(
                            ByteString.copyFromUtf8(Long.toString(ProcessHandle.current()
                                .pid())))
                        .build());
            }
        }
    }

    public static class Descendant {
        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000);
        }
    }
}
