package org.permmath.rendering.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.permmath.rendering.SvgOutputPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SQLite/PostgreSQL backing cache. All timestamps are database-clock epoch milliseconds. */
public final class JdbcImageCache implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JdbcImageCache.class);
    private final DataSource source;
    private final long ttlMillis;
    private final long maximumBytes;
    private final AutoCloseable owner;
    private final String now;
    private final String greatest;
    private final ScheduledExecutorService maintenance;
    private final AtomicLong nextWarning = new AtomicLong();

    public JdbcImageCache(DataSource source, boolean sqlite, Duration ttl, long maximumBytes,
            Duration cleanupInterval, AutoCloseable owner) {
        this.source = source;
        this.ttlMillis = ttl.toMillis();
        this.maximumBytes = maximumBytes;
        this.owner = owner;
        this.now = sqlite ? "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
                : "CAST(extract(epoch FROM statement_timestamp()) * 1000 AS bigint)";
        this.greatest = sqlite ? "max" : "greatest";
        if (ttlMillis < 1 || maximumBytes < 1 || cleanupInterval.toMillis() < 1) {
            throw new IllegalArgumentException("Persistent cache limits must be positive");
        }
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "image-cache-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(this::cleanUp, cleanupInterval.toMillis(),
                cleanupInterval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("event=persistent_cache_initialized backend={} maximumBytes={} ttlMillis={}",
                sqlite ? "sqlite" : "postgres", maximumBytes, ttlMillis);
    }

    public String find(String key) {
        String stored;
        try (var connection = source.getConnection(); var query = connection.prepareStatement(
                "SELECT svg FROM permmath_image_cache WHERE cache_key = ? AND expires_at > " + now
                + " AND size_bytes <= ?")) {
            query.setString(1, key);
            query.setLong(2, maximumBytes);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return null;
                // Revalidate at the storage boundary; policy versions also participate in cache keys.
                stored = rows.getString(1);
            }
        } catch (SQLException failure) {
            warn("read", failure);
            return null;
        }
        try {
            return SvgOutputPolicy.sanitize(stored);
        } catch (IOException | RuntimeException invalid) {
            discard(key);
            warn("invalid_output", invalid);
            return null;
        }
    }

    /** A regenerated entry replaces expired/corrupt output; a hit only refreshes metadata. */
    public void save(String key, String svg) {
        long bytes = svg.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > maximumBytes) return;
        try (var connection = source.getConnection(); var insert = connection.prepareStatement(
                "INSERT INTO permmath_image_cache(cache_key, svg, size_bytes, created_at, last_access_at, expires_at) "
                + "VALUES (?, ?, ?, " + now + ", " + now + ", " + now + " + ?) "
                + "ON CONFLICT (cache_key) DO UPDATE SET svg = EXCLUDED.svg, size_bytes = EXCLUDED.size_bytes, "
                + "created_at = EXCLUDED.created_at, last_access_at = "
                + greatest + "(permmath_image_cache.last_access_at, EXCLUDED.last_access_at), expires_at = "
                + greatest + "(permmath_image_cache.expires_at, EXCLUDED.expires_at)")) {
            insert.setString(1, key);
            insert.setString(2, svg);
            insert.setLong(3, bytes);
            insert.setLong(4, ttlMillis);
            insert.executeUpdate();
        } catch (SQLException failure) {
            warn("write", failure);
        }
    }

    /** Refresh idle expiry synchronously on every successful access, including memory hits. */
    public void accessed(String key, String svg) {
        long bytes = svg.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > maximumBytes) return;
        try (var connection = source.getConnection()) {
            // Ordinary hits update only metadata, without sending the SVG back to the database.
            try (var update = connection.prepareStatement("UPDATE permmath_image_cache SET last_access_at = "
                    + greatest + "(last_access_at, " + now + "), expires_at = "
                    + greatest + "(expires_at, " + now + " + ?) WHERE cache_key = ?")) {
                update.setLong(1, ttlMillis);
                update.setString(2, key);
                if (update.executeUpdate() != 0) return;
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO permmath_image_cache(cache_key, svg, size_bytes, created_at, last_access_at, expires_at) "
                    + "VALUES (?, ?, ?, " + now + ", " + now + ", " + now + " + ?) "
                    + "ON CONFLICT (cache_key) DO UPDATE SET last_access_at = "
                    + greatest + "(permmath_image_cache.last_access_at, EXCLUDED.last_access_at), expires_at = "
                    + greatest + "(permmath_image_cache.expires_at, EXCLUDED.expires_at)")) {
                insert.setString(1, key);
                insert.setString(2, svg);
                insert.setLong(3, bytes);
                insert.setLong(4, ttlMillis);
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            warn("write", failure);
        }
    }

    private void discard(String key) {
        try (var connection = source.getConnection(); var delete = connection.prepareStatement(
                "DELETE FROM permmath_image_cache WHERE cache_key = ?")) {
            delete.setString(1, key);
            delete.executeUpdate();
        } catch (SQLException failure) {
            warn("discard", failure);
        }
    }

    public void cleanUp() {
        try (var connection = source.getConnection(); var expired = connection.createStatement()) {
            expired.executeUpdate("DELETE FROM permmath_image_cache WHERE expires_at <= " + now);
            try (var budget = connection.prepareStatement("""
                    DELETE FROM permmath_image_cache WHERE cache_key IN (
                        SELECT cache_key FROM (
                            SELECT cache_key, sum(size_bytes) OVER (
                                ORDER BY last_access_at DESC, cache_key ROWS UNBOUNDED PRECEDING) AS retained_bytes
                            FROM permmath_image_cache
                        ) entries WHERE retained_bytes > ?
                    )
                    """)) {
                budget.setLong(1, maximumBytes);
                budget.executeUpdate();
            }
        } catch (SQLException failure) {
            warn("cleanup", failure);
        }
    }

    private void warn(String operation, Exception failure) {
        // No SQL/content/credentials in logs, and no repeated warning flood during an outage.
        long time = System.nanoTime();
        long next = nextWarning.get();
        if (time >= next && nextWarning.compareAndSet(next, time + TimeUnit.MINUTES.toNanos(1))) {
            log.warn("event=persistent_cache_unavailable operation={} errorType={}", operation,
                    failure.getClass().getSimpleName());
        }
    }

    @Override public void close() throws Exception {
        maintenance.shutdownNow();
        maintenance.awaitTermination(5, TimeUnit.SECONDS);
        if (owner != null) owner.close();
    }
}
