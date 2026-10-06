package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import com.google.protobuf.ByteString;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.permmath.contract.v1.*;

class SupervisedRendererTests {

    @TempDir
    Path temp;

    static RenderRequest request() {
        return RenderRequest.newBuilder().setRequestId("test-job")
                .setFormula(AsciiMathFormula.newBuilder().setFormula("x")).build();
    }

    ProcessBuilder child(String mode) {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                Child.class.getName(), mode, temp.resolve("pid").toString()).redirectError(ProcessBuilder.Redirect.INHERIT);
    }

    @Test
    void transfersProtobufAndChecksCorrelation() throws Exception {
        assertEquals("<svg/>", SupervisedRenderer.run(child("ok"), request(), Duration.ofSeconds(10)).getSvg().toStringUtf8());
        assertEquals(RenderException.Kind.FAILED, assertThrows(RenderException.class,
                () -> SupervisedRenderer.run(child("wrong-id"), request(), Duration.ofSeconds(10))).kind());
    }

    @Test
    void killsStalledProcessAndCanRenderNextJob() throws Exception {
        assertEquals(RenderException.Kind.DEADLINE, assertThrows(RenderException.class,
                () -> SupervisedRenderer.run(child("hang"), request(), Duration.ofSeconds(2))).kind());
        long pid = Long.parseLong(Files.readString(temp.resolve("pid")));
        for (int i = 0; i < 50 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) {
            Thread.sleep(20);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        assertFalse(SupervisedRenderer.run(child("ok"), request(), Duration.ofSeconds(10)).getSvg().isEmpty());
    }

    @Test
    void queueCancellationKillsRunningChildAndRetainsAdmissionUntilExit() throws Exception {
        try (var queue = new RenderWorkQueue(1, new RenderQueueSettings(0, 1_000_000, Duration.ofSeconds(5)),
                    (request, budget) -> SupervisedRenderer.run(child(request.getRequestId()), request, budget));
                var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var hanging = request().toBuilder().setRequestId("hang").build();
            var active = callers.submit(() -> queue.execute(hanging, Duration.ofSeconds(10)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(temp.resolve("pid")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(temp.resolve("pid")));
            long pid = Long.parseLong(Files.readString(temp.resolve("pid")));
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            active.cancel(true);
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((queue.retainedBytes() != 0 || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, queue.retainedBytes());
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(queue.execute(request().toBuilder().setRequestId("ok").build(), Duration.ofSeconds(5)).getSvg().isEmpty());
        }
    }

    @Test
    void rejectsMissingInputAndInvalidWidth() {
        assertThrows(RenderException.class, () -> RenderLimits.validate(RenderRequest.newBuilder().setRequestId("x").build()));
        assertThrows(RenderException.class, () -> RenderLimits.validate(RenderRequest.newBuilder().setRequestId("x")
                .setHtml(HtmlDocument.newBuilder().setDocument("x").setWidth(219)).build()));
    }

    public static class Child {

        public static void main(String[] args) throws Exception {
            var request = RenderRequest.parseFrom(System.in);
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if (args[0].equals("hang")) {
                Thread.sleep(60_000);
            }
            RenderResponse.newBuilder().setRequestId(args[0].equals("wrong-id") ? "wrong" : request.getRequestId())
                    .setSvg(ByteString.copyFromUtf8("<svg/>" )).build().writeTo(System.out);
        }
    }
}
