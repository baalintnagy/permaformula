package org.permaformula.rendering.cache;

import static org.permaformula.validation.QuickValidator.validate;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;

import org.permaformula.compression.StringCompressionService;
import org.permaformula.rendering.JpegOutputPolicy;
import org.permaformula.rendering.SvgOutputPolicy;
import org.permaformula.rendering.UbcqIdentity;
import org.permaformula.rendering.WorkDeadline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** SQLite/PostgreSQL backing cache. All timestamps are database-clock epoch milliseconds. */
public final class JdbcImageCache implements AutoCloseable {
    public static final long LIBRARY_SOURCE_BUDGET = 256 * 1024;
    public static final long LIBRARY_UBCQ_BUDGET = 2 * 1024 * 1024;
    private static final Logger log = LoggerFactory.getLogger(JdbcImageCache.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final long ttlMillis;
    private final long maximumBytes;
    private final AutoCloseable owner;
    private final String now;
    private final String greatest;
    private final ScheduledExecutorService maintenance;
    private final AtomicLong nextWarning = new AtomicLong();
    private final Semaphore work = new Semaphore(2);
    private final Semaphore metadata = new Semaphore(2);
    private final Semaphore touchSlots = new Semaphore(1024);
    private final ConcurrentHashMap<String, Long> touches = new ConcurrentHashMap<>();
    private final AtomicLong droppedTouches = new AtomicLong();
    private final long touchIntervalMillis;
    private final long retentionMillis;
    private final boolean sqlite;
    private final BooleanSupplier writable;
    private static final long ROW_OVERHEAD = 1024;
    private final long maximumEntries;

    public JdbcImageCache(DataSource source, boolean sqlite, Duration ttl, long maximumBytes,
        Duration cleanupInterval, AutoCloseable owner) {
        this(source, sqlite, ttl, maximumBytes, cleanupInterval, owner, () -> true);
    }

    public JdbcImageCache(DataSource source, boolean sqlite, Duration ttl, long maximumBytes,
        Duration cleanupInterval, AutoCloseable owner,
        BooleanSupplier writable) {
        this(source, sqlite, ttl, maximumBytes, cleanupInterval, owner, writable, 10_000);
    }

    public JdbcImageCache(DataSource source, boolean sqlite, Duration ttl, long maximumBytes,
        Duration cleanupInterval, AutoCloseable owner, BooleanSupplier writable,
        long maximumEntries) {
        this.maximumEntries = maximumEntries;
        this.sqlite = sqlite;
        this.writable = writable;
        jdbc = new NamedParameterJdbcTemplate(new DeadlineJdbcTemplate(source));
        transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        // Each cache write owns its lock and commit independently of caller transactions.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ttlMillis = ttl.toMillis();
        touchIntervalMillis = Math.max(1, Math.min(30_000, ttlMillis / 4));
        retentionMillis = Math.addExact(ttlMillis, 2 * touchIntervalMillis);
        this.maximumBytes = maximumBytes;
        this.owner = owner;
        this.now = sqlite ? "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
            : "CAST(extract(epoch FROM statement_timestamp()) * 1000 AS bigint)";
        this.greatest = sqlite ? "max" : "greatest";
        validate(
            !(ttlMillis < 1
                || maximumBytes < 1
                || cleanupInterval.toMillis() < 1
                || maximumEntries < 1
                || maximumEntries > 1_000_000),
            "Persistent cache limits must be positive");
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "image-cache-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleWithFixedDelay(this::cleanUp, cleanupInterval.toMillis(),
            cleanupInterval.toMillis(),
            TimeUnit.MILLISECONDS);
        maintenance.scheduleWithFixedDelay(this::flushTouches, touchIntervalMillis,
            touchIntervalMillis, TimeUnit.MILLISECONDS);
        log.info(
            """
            event=persistent_cache_initialized backend={} maximumBytes={} maximumEntries={} \
            ttlMillis={}\
            """
            ,
            sqlite ? "sqlite" : "postgres", maximumBytes, maximumEntries, ttlMillis);
    }

    public record Stored(String output, boolean described) {
    }

    public String find(String key) {
        Stored stored = findStored(key);
        return stored == null ? null
            : stored.output();
    }

    public Stored findStored(String key) {
        try (Permit _ = admit()) {
            return loadStored(key);
        }
    }

    private Stored loadStored(String key) {
        Stored loaded;
        try {
            // Bind visit freshness after acquiring the connection, which may have waited.
            loaded = jdbc.getJdbcOperations()
                .query(
                    """
                    SELECT svg, descriptor IS NOT NULL
                    FROM permaformula_image_cache
                    WHERE cache_key = ?
                        AND (expires_at > %s OR ?)
                        AND size_bytes <= ?
                    """
                        .formatted(now),
                    query -> {
                        query.setString(1, key);
                        Long touched = touches.get(key);
                        query.setBoolean(2, touched != null
                            && TimeUnit.NANOSECONDS
                                .toMillis(System.nanoTime() - touched) < ttlMillis);
                        query.setLong(3, maximumBytes);
                    },
                    rows -> rows.next()
                        ? new Stored(rows.getString(1),
                            rows.getBoolean(2))
                        : null);
        } catch (DataAccessException failure) {
            warn("read", failure);
            return null;
        }
        if (loaded == null) {
            return null;
        }
        WorkDeadline.remainingNanos(Duration.ofSeconds(2));
        try {
            if (loaded.output()
                .startsWith(JpegOutputPolicy.PREFIX)) {
                JpegOutputPolicy.decode(loaded.output());
                WorkDeadline.remainingNanos(Duration.ofSeconds(2));
                return loaded;
            }
            // Revalidate at the storage boundary; policy versions also participate in cache keys.
            String clean = SvgOutputPolicy.sanitize(loaded.output());
            WorkDeadline.remainingNanos(Duration.ofSeconds(2));
            return new Stored(clean, loaded.described());
        } catch (ResponseStatusException denied) {
            throw denied;
        } catch (IOException | RuntimeException invalid) {
            discard(key);
            warn("invalid_output", invalid);
            return null;
        }
    }

    /** A regenerated entry replaces expired/corrupt output; a hit only refreshes metadata. */
    public void save(String key, String svg) {
        save(key, svg, false);
    }

    public void saveIfAbsent(String key, String svg) {
        save(key, svg, true);
    }

    private void save(String key, String svg, boolean onlyAbsent) {
        try (Permit _ = admit()) {
            write(key, svg, onlyAbsent);
        }
    }

    private void write(String key, String svg, boolean onlyAbsent) {
        if (!writable.getAsBoolean()) {
            pressure();
            return;
        }
        long bytes = svg.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0
            || bytes > maximumBytes) {
            return;
        }
        try {
            transactions.executeWithoutResult(status -> {
                lockWrites();
                if (!fits(key, bytes, false)) {
                    status.setRollbackOnly();
                    return;
                }
                String conflict = onlyAbsent ? "ON CONFLICT (cache_key) DO NOTHING" :
                    """
                    ON CONFLICT (cache_key) DO UPDATE
                    SET svg = EXCLUDED.svg,
                        descriptor = NULL,
                        editor_kind = NULL,
                        source_excerpt = NULL,
                        descriptor_bytes = NULL,
                        ubcq = NULL,
                        ubcq_key = NULL,
                        ubcq_bytes = NULL,
                        size_bytes = EXCLUDED.size_bytes,
                        created_at = EXCLUDED.created_at,
                        last_access_at = %s(
                            permaformula_image_cache.last_access_at, EXCLUDED.last_access_at),
                        expires_at = %s(
                            permaformula_image_cache.expires_at, EXCLUDED.expires_at)
                    """
                        .formatted(greatest, greatest);
                jdbc.update(
                    """
                    INSERT INTO permaformula_image_cache(
                        cache_key, svg, size_bytes, created_at, last_access_at, expires_at
                    )
                    VALUES (:key, :svg, :bytes, %s, %s, %s + :retentionMillis)
                    %s
                    """
                        .formatted(now, now, now, conflict),
                    new MapSqlParameterSource()
                        .addValue("key", key)
                        .addValue("svg", svg)
                        .addValue("bytes", bytes)
                        .addValue("retentionMillis", retentionMillis));
            });
        } catch (DataAccessException | TransactionException failure) {
            warn("write", failure);
        }
    }

    /** One timestamp per key, no JDBC, payload copies or unbounded event queue. */
    public void accessed(String key) {
        touches.compute(key, (_, previous) -> {
            if (previous == null
                && !touchSlots.tryAcquire()) {
                droppedTouches.incrementAndGet();
                return null;
            }
            return System.nanoTime();
        });
    }

    public boolean flushTouches() {
        if (!writable.getAsBoolean()) {
            pressure();
            return false;
        }
        long dropped = droppedTouches.getAndSet(0);
        if (dropped > 0) {
            log.warn("event=persistent_touch_capacity droppedVisits={} maximumPending=1024",
                dropped);
        }
        List<Entry<String, Long>> batch = touches.entrySet()
            .stream()
            .map(entry -> Map.entry(entry.getKey(),
                entry.getValue()))
            .toList();
        if (batch.isEmpty()) {
            return true;
        }

        try (Permit _ = admit()) {
            jdbc.getJdbcOperations()
                .execute(
                    """
                    UPDATE permaformula_image_cache
                    SET last_access_at = %s(last_access_at, %s - ?),
                        expires_at = %s(expires_at, %s - ? + ?)
                    WHERE cache_key = ?
                    """
                        .formatted(greatest, now, greatest, now),
                    (PreparedStatementCallback<Void>) update -> {
                        for (var visit : batch) {
                            long age = Math.max(0,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime()
                                    - visit.getValue()));
                            update.setLong(1, age);
                            update.setLong(2, age);
                            update.setLong(3, retentionMillis);
                            update.setString(4, visit.getKey());
                            update.addBatch();
                        }
                        update.executeBatch();
                        return null;
                    });
            for (var visit : batch) {
                if (touches.remove(visit.getKey(),
                    visit.getValue())) {
                    touchSlots.release();
                }
            }
            return true;
        } catch (RuntimeException failure) {
            warn("touch", failure);
            return false;
        }
    }

    public Boolean sourcePublished(String key) {
        try (Permit _ = admit(metadata)) {
            return published(key);
        } catch (DataAccessException failure) {
            warn("metadata", failure);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Persistent cache unavailable");
        }
    }

    private Boolean published(String key) {
        return jdbc.query(
            """
            SELECT descriptor IS NOT NULL
            FROM permaformula_image_cache
            WHERE cache_key = :key
            """
            , Map.of("key", key),
            rows -> rows.next()
                ? rows.getBoolean(1)
                : null);
    }

    /** The indexed digest was recorded with the validated output, never supplied as a client ID. */
    public String keyForToken(String tokenKey) {
        try (Permit _ = admit(metadata)) {
            return jdbc.query(
                """
                SELECT cache_key
                FROM permaformula_image_cache
                WHERE ubcq_key = :tokenKey
                    AND descriptor IS NOT NULL
                    AND expires_at > %s
                    AND size_bytes <= :maximumBytes
                LIMIT 1
                """
                    .formatted(now),
                Map.of("tokenKey", tokenKey, "maximumBytes", maximumBytes),
                rows -> rows.next()
                    ? rows.getString(1)
                    : null);
        } catch (DataAccessException failure) {
            warn("token_lookup", failure);
            return null;
        }
    }

    public boolean describe(String key, String kind, String descriptor, String sourceExcerpt) {
        return describe(key, kind, descriptor, sourceExcerpt,
            UbcqIdentity.context(kind, 500));
    }

    public boolean describe(String key, String kind, String descriptor, String sourceExcerpt,
        String tokenContext) {
        try (Permit _ = admit()) {
            return writeDescription(key, kind, descriptor, sourceExcerpt, tokenContext);
        }
    }

    private boolean writeDescription(String key, String kind, String descriptor,
        String sourceExcerpt, String tokenContext) {
        if (!writable.getAsBoolean()) {
            pressure();
            return false;
        }
        int end = 0;
        for (int count = 0; end < sourceExcerpt.length()
            && count < 256; count++) {
            end += Character.charCount(sourceExcerpt.codePointAt(end));
        }
        String excerpt = sourceExcerpt.substring(0, end);
        long descriptorBytes = descriptor.getBytes(StandardCharsets.UTF_8).length;
        String ubcq;
        try {
            ubcq = new StringCompressionService()
                .compress(descriptor);
        } catch (IOException failure) {
            warn("compress", failure);
            return false;
        }
        String tokenKey = UbcqIdentity.key(tokenContext, ubcq);
        long bytes = descriptorBytes + excerpt.getBytes(StandardCharsets.UTF_8).length
            + ubcq.length();

        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                lockWrites();
                if (!fits(key, bytes, true)) {
                    status.setRollbackOnly();
                    return false;
                }
                int updated = jdbc.update(
                    """
                    UPDATE permaformula_image_cache
                    SET descriptor = :descriptor,
                        editor_kind = :kind,
                        size_bytes = size_bytes + :bytes,
                        source_excerpt = :excerpt,
                        descriptor_bytes = :descriptorBytes,
                        ubcq = :ubcq,
                        ubcq_key = :tokenKey,
                        ubcq_bytes = :ubcqBytes
                    WHERE cache_key = :key
                        AND descriptor IS NULL
                        AND size_bytes + :bytes <= :maximumBytes
                    """
                    , new MapSqlParameterSource()
                        .addValue("descriptor", descriptor)
                        .addValue("kind", kind)
                        .addValue("bytes", bytes)
                        .addValue("excerpt", excerpt)
                        .addValue("descriptorBytes", descriptorBytes)
                        .addValue("ubcq", ubcq)
                        .addValue("tokenKey", tokenKey)
                        .addValue("ubcqBytes", ubcq.length())
                        .addValue("key", key)
                        .addValue("maximumBytes", maximumBytes));
                return updated > 0
                    || Boolean.TRUE.equals(published(key));
            }));
        } catch (DataAccessException | TransactionException failure) {
            warn("describe", failure);
            return false;
        }
    }

    public record LibraryItem(String id, String kind, String descriptor, long createdAt,
        String ubcq) {
    }

    public record LibrarySummary(String id, String kind, String excerpt, long createdAt,
        String descriptor, String ubcq, boolean lookahead) {
        public LibrarySummary(String id, String kind, String excerpt, long createdAt,
            String descriptor, String ubcq) {
            this(id, kind, excerpt, createdAt, descriptor, ubcq, false);
        }

        public LibrarySummary(String id, String kind, String excerpt, long createdAt) {
            this(id, kind, excerpt, createdAt, null, null);
        }

        public LibrarySummary(String id, String kind, String excerpt, long createdAt,
            String descriptor) {
            this(id, kind, excerpt, createdAt, descriptor, null);
        }
    }

    public record LibraryCursor(long createdAt, String id) {
    }

    public List<LibrarySummary> library(LibraryCursor cursor, int limit) throws SQLException {
        validate(
            !(limit < 1
                || limit > 24),
            "Invalid library limit");
        String cursorCondition = cursor == null ? ""
            : " AND (created_at, cache_key) < (:cursorCreatedAt, :cursorId)";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("maximumBytes", maximumBytes)
            .addValue("pageSize", limit + 1)
            .addValue("limit", limit)
            .addValue("unknownTokenBytes", LIBRARY_UBCQ_BUDGET + 1)
            .addValue("tokenBudget", LIBRARY_UBCQ_BUDGET)
            .addValue("unknownDescriptorBytes", LIBRARY_SOURCE_BUDGET + 1)
            .addValue("sourceBudget", LIBRARY_SOURCE_BUDGET);
        if (cursor != null) {
            parameters.addValue("cursorCreatedAt", cursor.createdAt())
                .addValue("cursorId", cursor.id());
        }
        try (Permit _ = admit(metadata)) {
            return jdbc.query(
                """
                WITH page AS (
                    SELECT
                        cache_key,
                        editor_kind,
                        substr(source_excerpt, 1, 256) AS excerpt,
                        created_at,
                        descriptor_bytes,
                        ubcq_bytes
                    FROM permaformula_image_cache
                    WHERE descriptor IS NOT NULL
                        AND expires_at > %s
                        AND size_bytes <= :maximumBytes%s
                    ORDER BY created_at DESC, cache_key DESC
                    LIMIT :pageSize
                ), ranked AS (
                    SELECT
                        page.*,
                        row_number() OVER (
                            ORDER BY created_at DESC, cache_key DESC
                        ) AS rn,
                        sum(coalesce(ubcq_bytes, :unknownTokenBytes)) OVER (
                            ORDER BY created_at DESC, cache_key DESC
                            ROWS UNBOUNDED PRECEDING
                        ) AS token_bytes
                    FROM page
                ), visible AS (
                    SELECT *
                    FROM ranked
                    WHERE rn <= :limit
                        AND (rn = 1 OR token_bytes <= :tokenBudget)
                ), visible_count AS (
                    SELECT count(*) AS n
                    FROM visible
                ), budget AS (
                    SELECT coalesce(
                        sum(coalesce(descriptor_bytes, :unknownDescriptorBytes)), 0
                    ) AS bytes
                    FROM visible
                )
                SELECT
                    p.cache_key,
                    p.editor_kind,
                    p.excerpt,
                    p.created_at,
                    CASE
                        WHEN b.bytes <= :sourceBudget
                            AND p.cache_key IN (SELECT cache_key FROM visible)
                        THEN c.descriptor
                        ELSE NULL
                    END,
                    CASE
                        WHEN p.rn <= v.n THEN c.ubcq
                        ELSE NULL
                    END,
                    p.rn > v.n
                FROM ranked p
                CROSS JOIN budget b
                CROSS JOIN visible_count v
                JOIN permaformula_image_cache c ON c.cache_key = p.cache_key
                WHERE p.rn <= v.n + 1
                ORDER BY p.created_at DESC, p.cache_key DESC
                """
                    .formatted(now, cursorCondition),
                parameters,
                (rows, _) -> new LibrarySummary(rows.getString(1),
                    rows.getString(2),
                    rows.getString(3),
                    rows.getLong(4),
                    rows.getString(5),
                    rows.getString(6),
                    rows.getBoolean(7)));
        } catch (DataAccessException failure) {
            throw sqlFailure(failure);
        }
    }

    public LibraryItem libraryItem(String id) throws SQLException {
        try (Permit _ = admit()) {
            return jdbc.query(
                """
                SELECT editor_kind, descriptor, created_at, ubcq
                FROM permaformula_image_cache
                WHERE cache_key = :id
                    AND descriptor IS NOT NULL
                    AND expires_at > %s
                    AND size_bytes <= :maximumBytes
                """
                    .formatted(now),
                Map.of("id", id, "maximumBytes", maximumBytes),
                rows -> rows.next()
                    ? new LibraryItem(id, rows.getString(1),
                        rows.getString(2),
                        rows.getLong(3),
                        rows.getString(4))
                    : null);
        } catch (DataAccessException failure) {
            throw sqlFailure(failure);
        }
    }

    public boolean delete(String key) throws SQLException {
        try (Permit _ = admit()) {
            boolean deleted = jdbc.update(
                "DELETE FROM permaformula_image_cache WHERE cache_key = :key",
                Map.of("key", key)) != 0;
            if (touches.remove(key) != null) {
                touchSlots.release();
            }
            return deleted;
        } catch (DataAccessException failure) {
            throw sqlFailure(failure);
        }
    }

    private static SQLException sqlFailure(DataAccessException failure) {
        // Preserve the checked failure contract used by library/admin callers.
        if (failure.getCause() instanceof SQLException sql) {
            return sql;
        }
        return new SQLException("Persistent cache unavailable", failure);
    }

    private void discard(String key) {
        try {
            jdbc.update("DELETE FROM permaformula_image_cache WHERE cache_key = :key",
                Map.of("key", key));
        } catch (DataAccessException failure) {
            warn("discard", failure);
        }
    }

    public void cleanUp() {
        // Do not discard actively visited rows while their timestamp flush is failing.
        if (!flushTouches()) {
            return;
        }
        try (Permit _ = admit()) {
            jdbc.getJdbcOperations()
                .update(
                    "DELETE FROM permaformula_image_cache WHERE expires_at <= %s".formatted(now));
            jdbc.update(
                """
                DELETE FROM permaformula_image_cache
                WHERE cache_key IN (
                    SELECT cache_key
                    FROM (
                        SELECT
                            cache_key,
                            row_number() OVER (
                                ORDER BY last_access_at DESC, cache_key
                            ) AS position,
                            sum(size_bytes) OVER (
                                ORDER BY last_access_at DESC, cache_key
                                ROWS UNBOUNDED PRECEDING
                            ) AS retained_bytes
                        FROM permaformula_image_cache
                    ) entries
                    WHERE retained_bytes > :maximumBytes
                        OR position > :maximumEntries
                )
                """
                , Map.of("maximumBytes", maximumBytes - maximumBytes / 5,
                    "maximumEntries", maximumEntries - maximumEntries / 10));
        } catch (DataAccessException | ResponseStatusException failure) {
            warn("cleanup", failure);
        }
    }

    // PostgreSQL serializes budget decisions across API processes; SQLite's no-op
    // write acquires its single writer lock before reading the aggregate budget.
    private void lockWrites() {
        if (sqlite) {
            jdbc.getJdbcOperations()
                .update(
                    "UPDATE permaformula_image_cache SET size_bytes = size_bytes WHERE 0 = 1");
        } else {
            jdbc.getJdbcOperations()
                .execute("SELECT pg_advisory_xact_lock(734829105)");
        }
    }

    private boolean fits(String key, long bytes, boolean description) {
        return Boolean.TRUE.equals(jdbc.query(
            """
            SELECT
                coalesce(sum(size_bytes), 0),
                count(*),
                coalesce(max(CASE
                    WHEN cache_key = :key THEN size_bytes
                    ELSE 0
                END), 0),
                coalesce(max(CASE
                    WHEN cache_key = :key AND descriptor IS NOT NULL THEN 1
                    ELSE 0
                END), 0)
            FROM permaformula_image_cache
            """
            , Map.of("key", key),
            rows -> {
                rows.next();
                long total = rows.getLong(1),
                    count = rows.getLong(2),
                    previous = rows.getLong(3);
                if (description
                    && rows.getInt(4) == 1) {
                    return true;
                }
                long resultingCount = count + (!description
                    && previous == 0 ? 1 : 0);
                long resultingBytes = total + bytes - (description ? 0 : previous);
                // Small test/development budgets still work; reserve at most 10%.
                long overhead = Math.min(maximumBytes / 10, resultingCount * ROW_OVERHEAD);
                boolean fits = resultingCount <= maximumEntries
                    && resultingBytes <= maximumBytes - overhead;
                if (!fits) {
                    pressure();
                }
                return fits;
            }));
    }

    private void pressure() {
        warn("write_budget_or_disk_pressure", new IllegalStateException());
    }

    private Permit admit() {
        return admit(work);
    }

    private Permit admit(Semaphore budget) {
        WorkDeadline.remainingNanos(Duration.ofSeconds(2));
        if (!budget.tryAcquire()) {
            throw busy();
        }
        return new Permit(budget);
    }

    private record Permit(Semaphore budget) implements AutoCloseable {
        @Override
        public void close() {
            budget.release();
        }
    }

    static ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Persistent cache busy; retry shortly") {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.set("Retry-After", "1");
                headers.setCacheControl("no-store");
                return headers;
            }
        };
    }

    private void warn(String operation, Exception failure) {
        // No SQL/content/credentials in logs, and no repeated warning flood during an outage.
        long time = System.nanoTime();
        long next = nextWarning.get();
        if (time >= next
            && nextWarning.compareAndSet(next, time + TimeUnit.MINUTES.toNanos(1))) {
            log.warn("event=persistent_cache_unavailable operation={} errorType={}", operation,
                failure.getClass()
                    .getSimpleName());
        }
    }

    @Override
    public void close() throws IOException {
        maintenance.shutdownNow();
        try (Closeable _ = this::closeOwner) {
            try {
                maintenance.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread()
                    .interrupt();
                throw new IOException("Interrupted while stopping cache maintenance", failure);
            } finally {
                flushTouches();
            }
        }
    }

    private void closeOwner() throws IOException {
        if (owner == null) {
            return;
        }
        try {
            owner.close();
        } catch (InterruptedException failure) {
            Thread.currentThread()
                .interrupt();
            throw new IOException("Interrupted while closing cache owner", failure);
        } catch (Exception failure) {
            throw new IOException("Failed to close cache owner", failure);
        }
    }
}
