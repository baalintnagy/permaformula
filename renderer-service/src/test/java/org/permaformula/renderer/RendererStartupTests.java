package org.permaformula.renderer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class RendererStartupTests {
    @Test
    void launchGroupCanObserveReadinessOnStandardOutput() throws Exception {
        var builder = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
            RendererServiceMain.class.getName());
        builder.environment().clear();
        builder.environment().put("RENDERER_PORT", "0");
        var process = builder.start();
        try (
            var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var readiness = tasks.submit(() -> reader.readLine());
                assertEquals("event=renderer_ready transport=tcp",
                    readiness.get(10, TimeUnit.SECONDS));
                assertTrue(process.isAlive(), "Readiness must mean the service is listening");
            } finally {
                // Terminate before closing the executor so failed readiness cannot leave a blocked
                // reader.
                var children = process.descendants().toList();
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                children.forEach(ProcessHandle::destroyForcibly);
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
