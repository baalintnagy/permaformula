package org.permaformula.rendering.cache;

import static org.junit.jupiter.api.Assertions.*;

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
        long now = Instant.now().getEpochSecond();
        for (String invalid : new String[] { "pressure " + now, "ok " + (now - 121),
                "ok " + (now + 60),
                "ok", "ok nope", "ok " + now + " extra", "x".repeat(129) }) {
            Files.writeString(file, invalid);
            assertFalse(pressure.getAsBoolean(), invalid);
        }
        Files.writeString(file, "ok " + now + "\n");
        assertTrue(pressure.getAsBoolean());
    }
}
