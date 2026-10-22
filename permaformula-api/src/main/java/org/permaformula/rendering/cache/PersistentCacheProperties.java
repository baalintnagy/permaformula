package org.permaformula.rendering.cache;

import static org.permaformula.validation.QuickValidator.validate;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.permaformula.validation.RecordValidation;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("permaformula.image-cache.persistence")
public record PersistentCacheProperties(
    @DefaultValue("sqlite") @NotNull Backend backend,
    @DefaultValue(".runtime/image-cache.sqlite") @NotNull Path sqlitePath,
    @DefaultValue("jdbc:postgresql://localhost:5432/permaformula") String postgresUrl,
    @DefaultValue("permaformula") String username,
    @DefaultValue("") String password,
    @DefaultValue("4") @Min(1) @Max(32) int maximumPoolSize,
    @DefaultValue("2GB") @NotNull DataSize maximumSize,
    @DefaultValue("5m") @NotNull Duration cleanupInterval,
    @DefaultValue("10000") @Min(1) @Max(1_000_000) long maximumEntries) {
    public enum Backend {
        SQLITE, POSTGRES, MEMORY
    }

    public PersistentCacheProperties {
        RecordValidation.requireValid(PersistentCacheProperties.class, backend, sqlitePath,
            postgresUrl, username, password, maximumPoolSize, maximumSize, cleanupInterval,
            maximumEntries);
        validate(
            !(maximumSize.toBytes() < 1
                || cleanupInterval.toMillis() < 1),
            () -> new IllegalArgumentException(
                "Invalid persistent cache backend, pool, size or cleanup interval"));
        validate(
            !(backend == Backend.POSTGRES
                && (postgresUrl == null
                    || !postgresUrl.startsWith("jdbc:postgresql:")
                    || !StringUtils.isNotBlank(username)
                    || !StringUtils.isNotBlank(password))),
            () -> new IllegalArgumentException(
                "PostgreSQL cache requires a JDBC URL, username and password"));
    }

    @Override
    public String toString() {
        return "PersistentCacheProperties[backend=%s, credentials=redacted]".formatted(backend);
    }
}
