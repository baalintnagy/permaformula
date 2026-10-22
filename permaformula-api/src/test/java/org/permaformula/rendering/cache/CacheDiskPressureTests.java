package org.permaformula.rendering.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CacheDiskPressureTests {
    @TempDir
    Path directory;

    @Test
    void requiresFreshHealthySignalAndFailsClosed() throws Exception {
        var file = directory.resolve("pressure");
        var pressure = new CacheDiskPressure(file.toString(), null);
        assertFalse(pressure.getAsBoolean());
        long now = Instant.now()
            .getEpochSecond();
        for (String invalid : new String[] { "pressure %s".formatted(now),
                "ok %s".formatted((now - 121)),
                "ok %s".formatted((now + 60)),
                "ok", "ok nope", "ok %s extra".formatted(now),
                "x".repeat(129) }) {
            Files.writeString(file, invalid);
            assertFalse(pressure.getAsBoolean(), invalid);
        }
        Files.writeString(file,
            """
            ok %s
            \
            """
                .formatted(
                    now));
        assertTrue(pressure.getAsBoolean());
    }
}
