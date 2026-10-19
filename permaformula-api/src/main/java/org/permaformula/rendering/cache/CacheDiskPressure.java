package org.permaformula.rendering.cache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import org.apache.commons.lang3.StringUtils;

/** The signal is produced beside PostgreSQL, never inferred from API container space. */
final class CacheDiskPressure implements BooleanSupplier {
    private final Path signal;
    private final Path local;

    CacheDiskPressure(String signal, Path local) {
        this.signal = !StringUtils.isNotBlank(signal) ? null : Path.of(signal);
        this.local = local;
    }

    @Override
    public boolean getAsBoolean() {
        try {
            if (signal == null) {
                return local == null
                    || Files.getFileStore(local).getUsableSpace() >= 128L * 1024 * 1024;
            }
            if (Files.size(signal) > 128) {
                return false;
            }
            String[] fields = Files.readString(signal).trim().split("\\s+");
            long age = Instant.now().getEpochSecond() - Long.parseLong(fields[1]);
            return fields.length == 2
                && fields[0].equals("ok")
                && age >= 0
                && age <= 120;
        } catch (Exception _) {
            return false;
        }
    }
}
