package org.permmath.rendering.cache;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("permmath.image-cache.persistence")
public record PersistentCacheProperties(
        @DefaultValue("sqlite") Backend backend,
        @DefaultValue(".runtime/image-cache.sqlite") Path sqlitePath,
        @DefaultValue("jdbc:postgresql://localhost:5432/permmath") String postgresUrl,
        @DefaultValue("permmath") String username,
        @DefaultValue("") String password,
        @DefaultValue("4") int maximumPoolSize,
        @DefaultValue("2GB") DataSize maximumSize,
        @DefaultValue("5m") Duration cleanupInterval) {
    public enum Backend { SQLITE, POSTGRES, MEMORY }

    public PersistentCacheProperties {
        if (backend == null || sqlitePath == null || maximumPoolSize < 1 || maximumPoolSize > 32
                || maximumSize.toBytes() < 1 || cleanupInterval.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid persistent cache backend, pool, size or cleanup interval");
        }
        if (backend == Backend.POSTGRES && (postgresUrl == null || !postgresUrl.startsWith("jdbc:postgresql:")
                || username == null || username.isBlank() || password == null || password.isBlank())) {
            throw new IllegalArgumentException("PostgreSQL cache requires a JDBC URL, username and password");
        }
    }

    @Override public String toString() {
        return "PersistentCacheProperties[backend=" + backend + ", credentials=redacted]";
    }
}
