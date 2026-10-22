package org.permaformula.rendering.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import com.github.benmanes.caffeine.cache.Ticker;
import com.sun.management.ThreadMXBean;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.permaformula.TestServices;
import org.permaformula.compression.StringCompressionService;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.rendering.JpegOutputPolicy;
import org.permaformula.rendering.SvgOutputPolicy;
import org.permaformula.rendering.UbcqIdentity;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

/** Same persistence/TTL contract is exercised against real SQLite and PostgreSQL. */
abstract class JdbcImageCacheContract {
    static final String KEY = "a".repeat(64);
    static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>first</text></svg>";
    static final Duration TTL = Duration.ofHours(1);
    HikariDataSource source;
    private final List<JdbcImageCache> caches = new ArrayList<>();
    private final List<CaffeineImageStore> fronts = new ArrayList<>();

    abstract HikariDataSource openDatabase();

    abstract boolean sqlite();

    @BeforeEach
    void setUp() throws Exception {
        source = openDatabase();
        CacheSchema.migrate(source);
        backing(4096);
        execute("DELETE FROM permaformula_image_cache");
    }

    JdbcImageCache backing(long bytes) throws Exception {
        var cache = new JdbcImageCache(source, sqlite(), TTL, bytes, Duration.ofDays(1), null);
        caches.add(cache);
        return cache;
    }

    CaffeineImageStore front(JdbcImageCache backing) {
        var store = new CaffeineImageStore(new ImageCacheProperties(DataSize.ofBytes(4096), TTL),
            Ticker.systemTicker(),
            backing);
        fronts.add(store);
        return store;
    }

    @Test
    void deletionEvictsPersistentAndMemoryCopies() throws Exception {
        var backing = backing(4096);
        var store = front(backing);
        store.getOrGenerate(KEY, () -> SVG);
        backing.describe(KEY, "formula", "{\"forumla\":\"x\"}", "x");
        assertNotNull(backing.libraryItem(KEY));
        store.delete(KEY);
        assertNull(backing.find(KEY));
        assertNull(backing.libraryItem(KEY));
        var generations = new AtomicInteger();
        store.getOrGenerate(KEY, () -> {
            generations.incrementAndGet();
            return SVG;
        });
        assertEquals(1, generations.get());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var front : fronts) {
            front.close();
        }
        for (var cache : caches) {
            cache.close();
        }
        if (source != null) {
            source.close();
        }
    }

    void execute(String sql) throws Exception {
        try (var connection = source.getConnection();
            var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    long scalar(String sql) throws Exception {
        try (var connection = source.getConnection();
            var statement = connection.createStatement();
            var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @Test
    void jpegSurvivesMemoryCacheReplacementAndCorruptJpegIsDiscarded() throws Exception {
        var image = new BufferedImage(20, 10,
            BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", output);
        String jpeg = JpegOutputPolicy.encode(output.toByteArray());
        var backing = backing(4096);
        assertEquals(jpeg, front(backing)
            .getOrGenerate(KEY, () -> jpeg));
        assertEquals(jpeg, front(backing)
            .getOrGenerate(KEY, () -> {
                throw new IOException("Must hit database");
            }));
        backing.save(KEY, "jpeg-v1:bad-data");
        assertNull(backing.find(KEY));
        assertEquals(jpeg, front(backing)
            .getOrGenerate(KEY, () -> jpeg));
    }

    @Test
    void survivesClosingAndReopeningTheDatabaseAndMemoryCache() throws Exception {
        var backing = backing(4096);
        front(backing)
            .getOrGenerate(KEY, () -> SVG);
        backing.close();
        source.close();
        source = openDatabase();
        CacheSchema.migrate(source);
        var restarted = front(backing(4096));
        assertEquals(SvgOutputPolicy.sanitize(SVG),
            restarted.getOrGenerate(KEY, () -> {
                throw new AssertionError("Restart must load the persistent entry");
            }));
    }

    @Test
    void memoryHitsCoalesceExpiryAndDoNotRewriteSvg() throws Exception {
        var backing = backing(4096);
        var store = front(backing);
        store.getOrGenerate(KEY, () -> SVG);
        execute("UPDATE permaformula_image_cache SET last_access_at = 1, expires_at = 2");
        assertEquals(SVG, store.getOrGenerate(KEY, () -> {
            throw new AssertionError("Memory hit");
        }));
        assertEquals(1, scalar("SELECT last_access_at FROM permaformula_image_cache"));
        assertTrue(backing.flushTouches());
        assertEquals(TTL.toMillis() + 60_000,
            scalar("SELECT expires_at - last_access_at FROM permaformula_image_cache"));
        assertEquals(SVG.getBytes(StandardCharsets.UTF_8).length,
            scalar("SELECT size_bytes FROM permaformula_image_cache"));
        assertNotNull(backing(4096)
            .find(KEY));
    }

    @Test
    void expiredRowsAreNeverServedAndRegenerationReplacesTheirContent() throws Exception {
        var backing = backing(4096);
        front(backing)
            .getOrGenerate(KEY, () -> SVG);
        execute("UPDATE permaformula_image_cache SET expires_at = 0");
        assertNull(backing.find(KEY));
        String replacement = SVG.replace("first", "fresh");
        var restarted = front(backing(4096));
        assertEquals(replacement, restarted.getOrGenerate(KEY, () -> replacement));
        assertTrue(backing.find(KEY)
            .contains("fresh"));
    }

    @Test
    void hotMemoryImagesRemainAvailableWhenTheDatabaseIsUnavailable() throws Exception {
        var backing = backing(4096);
        var store = front(backing);
        assertEquals(SVG, store.getOrGenerate(KEY, () -> SVG));
        source.close();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            callers.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    assertEquals(SVG,
                        store.getOrGenerate(KEY, () -> fail("Must use memory during outage")));
                }
                return true;
            })
                .get(1, TimeUnit.SECONDS);
        }
        assertFalse(backing.flushTouches());
    }

    @Test
    void queuedTouchesUseVisitTimeAndNeverReinsertDeletedRows() throws Exception {
        var backing = backing(4096);
        backing.save(KEY, SVG);
        backing.accessed(KEY);
        Thread.sleep(100); // Distinguish the visit from the later flush timestamp.
        assertTrue(backing.flushTouches());
        String clock = sqlite() ? "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"
            : "CAST(extract(epoch FROM statement_timestamp()) * 1000 AS bigint)";
        assertTrue(
            scalar(
                "SELECT %s - last_access_at FROM permaformula_image_cache".formatted(clock)) >= 80);
        backing.accessed(KEY);
        backing.delete(KEY);
        assertTrue(backing.flushTouches());
        assertEquals(0, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void pendingVisitKeepsAnActiveRowReadableUntilFlushAndCleanup() throws Exception {
        var backing = backing(4096);
        backing.save(KEY, SVG);
        backing.accessed(KEY);
        execute("UPDATE permaformula_image_cache SET expires_at = 0");
        assertNotNull(backing.find(KEY));
        backing.cleanUp();
        assertNotNull(backing.find(KEY));
    }

    @Test
    void measuresColdValidationAtTheSupportedSvgCeiling() throws Exception {
        String prefix = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>";
        String suffix = "</text></svg>";
        int ceiling = ComplexityPolicy.CURRENT.get(
            ComplexityPolicy.Limit.SVG_BYTES);
        String large = "%s%s%s".formatted(
            prefix,
            "x".repeat(ceiling - prefix.length()
                - suffix.length()),
            suffix);
        var backing = backing(64L * 1024 * 1024);
        backing.save(KEY, large);
        var bean = ManagementFactory.getThreadMXBean();
        long before = bean instanceof ThreadMXBean allocation
            ? allocation.getThreadAllocatedBytes(Thread.currentThread()
                .threadId())
            : -1;
        long started = System.nanoTime();
        String loaded = backing.find(KEY);
        long elapsed = System.nanoTime() - started;
        long allocated = bean instanceof ThreadMXBean allocation
            && before >= 0
                ? allocation.getThreadAllocatedBytes(Thread.currentThread()
                    .threadId())
                    - before
                : -1;
        assertEquals(large, loaded);
        System.out.printf(
            """
            CACHE_COLD_MEASUREMENT backend=%s svgBytes=%d elapsedMs=%.1f threadAllocatedBytes=\
            %d%n\
            """
            ,
            sqlite() ? "sqlite" : "postgres", ceiling, elapsed / 1_000_000.0, allocated);
    }

    @Test
    void failuresAreNotPersistedAndRetryCanSucceed() throws Exception {
        var store = front(backing(4096));
        assertThrows(IOException.class, () -> store.getOrGenerate(KEY, () -> {
            throw new IOException("failed");
        }));
        assertEquals(0, scalar("SELECT count(*) FROM permaformula_image_cache"));
        assertEquals(SVG, store.getOrGenerate(KEY, () -> SVG));
        assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void concurrentCallersStillShareOneGeneration() throws Exception {
        var store = front(backing(4096));
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> store.getOrGenerate(KEY, () -> {
                    calls.incrementAndGet();
                    return SVG;
                })));
            }
            for (var result : results) {
                assertEquals(SVG, result.get(10, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, calls.get());
        assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void cleanupRemovesExpiredAndLeastRecentlyAccessedEntriesWithinTheByteBudget()
        throws Exception {
        var backing = backing(SVG.getBytes(StandardCharsets.UTF_8).length * 3L);
        for (String key : List.of("old", "recent", "newest", "expired")) {
            backing(4096)
                .save(key, SVG);
        }
        execute("UPDATE permaformula_image_cache SET last_access_at = 1 WHERE cache_key = 'old'");
        execute(
            "UPDATE permaformula_image_cache SET last_access_at = 2 WHERE cache_key = 'recent'");
        execute(
            "UPDATE permaformula_image_cache SET last_access_at = 3 WHERE cache_key = 'newest'");
        execute("UPDATE permaformula_image_cache SET expires_at = 0 WHERE cache_key = 'expired'");
        backing.cleanUp();
        assertNull(backing.find("old"));
        assertNull(backing.find("expired"));
        assertNotNull(backing.find("recent"));
        assertNotNull(backing.find("newest"));
        assertEquals(2, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void failedWriteRollsBackAndReleasesItsConnectionForRetry() throws Exception {
        var fail = new AtomicBoolean(true);
        var connections = new ArrayList<Connection>();
        var failingSource = new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                var connection = spy(source.getConnection());
                connections.add(connection);
                if (fail.get()) {
                    doThrow(new SQLException("injected insert failure"))
                        .when(connection)
                        .prepareStatement(startsWith("INSERT INTO"));
                }
                return connection;
            }
        };
        var cache = new JdbcImageCache(failingSource, sqlite(), TTL, 4096,
            Duration.ofDays(1),
            null);
        caches.add(cache);
        cache.save(KEY, SVG);
        assertEquals(0, scalar("SELECT count(*) FROM permaformula_image_cache"));
        verify(connections.getFirst())
            .rollback();
        verify(connections.getFirst())
            .setAutoCommit(true);
        verify(connections.getFirst())
            .close();
        fail.set(false);
        cache.save(KEY, SVG);
        assertNotNull(cache.find(KEY));
        assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void oversizedOutputIsReturnedButNotPersisted() throws Exception {
        var store = front(backing(8));
        assertEquals(SVG, store.getOrGenerate(KEY, () -> SVG));
        assertEquals(0, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void concurrentWritersAcrossCacheInstancesCannotExceedAggregateBudget() throws Exception {
        var first = backing(4096);
        var otherSource = openDatabase();
        var second = new JdbcImageCache(otherSource, sqlite(), TTL, 4096, Duration.ofDays(1),
            otherSource);
        caches.add(second);
        String payload = SVG.replace("first", "x".repeat(1200));
        try (var workers = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = workers.submit(() -> {
                start.await();
                first.save("one", payload);
                return null;
            });
            var b = workers.submit(() -> {
                start.await();
                second.save("two", payload);
                return null;
            });
            start.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
        }
        first.save("three", payload);
        first.save("four", payload);
        assertTrue(scalar("SELECT sum(size_bytes) FROM permaformula_image_cache") <= 4096 - 409);
        assertEquals(2, scalar("SELECT count(*) FROM permaformula_image_cache"));
        assertFalse(first.describe("one", "formula", "x".repeat(2000), "x"));
        assertTrue(scalar("SELECT sum(size_bytes) FROM permaformula_image_cache") <= 4096 - 409);
        first.delete("one");
        first.save("three", payload);
        assertNotNull(first.find("three"));
    }

    @Test
    void rowCountAndHeadroomLimitTinyEntryGrowth() throws Exception {
        execute(
            """
            WITH RECURSIVE numbers(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM numbers WHERE n < \
            10000) INSERT INTO permaformula_image_cache(cache_key,svg,size_bytes,created_at,\
            last_access_at,expires_at) SELECT CAST(n AS varchar(64)), 'x', 1, 1, n, \
            9999999999999 FROM numbers\
            """
        );
        var cache = backing(32L * 1024 * 1024);
        cache.save("extra", SVG);
        assertEquals(10000, scalar("SELECT count(*) FROM permaformula_image_cache"));
        cache.cleanUp();
        assertEquals(9000, scalar("SELECT count(*) FROM permaformula_image_cache"));
        cache.save("extra", SVG);
        assertNotNull(cache.find("extra"));
    }

    @Test
    void storedUbcqSurvivesRestartAndFindsValidatedOutputWithoutSourceDecoding() throws Exception {
        var cache = backing(4096);
        String descriptor = "{\"forumla\":\"x^2\"}";
        cache.save(KEY, SVG);
        assertTrue(cache.describe(KEY, "formula", descriptor, "x^2"));
        String token = cache.library(null, 1)
            .getFirst()
            .ubcq();
        assertEquals(descriptor,
            TestServices.validated(new StringCompressionService())
                .decompress(token));
        String digest = UbcqIdentity.key(UbcqIdentity.context("formula", 500), token);
        assertEquals(KEY, cache.keyForToken(digest));
        assertEquals(SvgOutputPolicy.sanitize(SVG),
            front(cache)
                .findByToken(digest));
        assertNull(cache.keyForToken(UbcqIdentity.key("old-policy", token)));
        assertNull(cache.keyForToken(UbcqIdentity.key(UbcqIdentity.context("html", 500), token)));
        cache.delete(KEY);
        assertNull(front(cache)
            .findByToken(digest));
    }

    @Test
    void preservesTrackedSchemaWithRenamedUbcqKey() throws Exception {
        backing(4096)
            .save(KEY, SVG);
        execute("ALTER TABLE permaformula_image_cache RENAME COLUMN ubcq_key TO legacy_ubcq_key");
        try {
            CacheSchema.migrate(source);
            assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
            assertEquals(5, scalar("SELECT count(*) FROM databasechangelog"));
            assertEquals(1, scalar(
                "SELECT count(*) FROM permaformula_image_cache WHERE legacy_ubcq_key IS NULL"));
        } finally {
            execute(
                "ALTER TABLE permaformula_image_cache RENAME COLUMN legacy_ubcq_key TO ubcq_key");
        }
    }

    @Test
    void configuredRowCapControlsBothAdmissionAndCleanup() throws Exception {
        var cache = new JdbcImageCache(source, sqlite(), TTL, 1_000_000, Duration.ofDays(1), null,
            () -> true, 20);
        caches.add(cache);
        for (int i = 0; i < 21; i++) {
            cache.save("configured-%s".formatted(i), SVG);
        }
        assertEquals(20, scalar("SELECT count(*) FROM permaformula_image_cache"));
        cache.cleanUp();
        assertEquals(18, scalar("SELECT count(*) FROM permaformula_image_cache"));
        cache.save("next", SVG);
        assertNotNull(cache.find("next"));
    }

    @Test
    void diskPressureSkipsWritesWithoutBreakingImagesAndRecovers() throws Exception {
        var writable = new AtomicBoolean(true);
        var cache = new JdbcImageCache(source, sqlite(), TTL, 4096, Duration.ofDays(1), null,
            writable::get);
        caches.add(cache);
        cache.save("existing", SVG);
        writable.set(false);
        var memory = front(cache);
        assertEquals(SVG, memory.getOrGenerate("new", () -> SVG));
        assertEquals(SVG, memory.getOrGenerate("new", () -> fail("Must stay in memory")));
        assertNotNull(cache.find("existing"));
        assertNull(cache.find("new"));
        assertFalse(cache.describe("existing", "formula", "{}", "x"));
        cache.accessed("existing");
        assertFalse(cache.flushTouches());
        writable.set(true);
        assertTrue(cache.flushTouches());
        assertTrue(cache.describe("existing", "formula", "{}", "x"));
        cache.save("new", SVG);
        assertNotNull(cache.find("new"));
    }

    @Test
    void persistentBudgetMeasuresUtf8BytesRatherThanJavaCharacters() throws Exception {
        String unicode = SVG.replace("first", "α");
        var store = front(backing(unicode.length()));
        assertEquals(unicode, store.getOrGenerate(KEY, () -> unicode));
        assertEquals(0, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void configuredIdleTtlExpiresWithoutCleanupOrAnApplicationRestart() throws Exception {
        var backing = new JdbcImageCache(source, sqlite(), Duration.ofMillis(150), 4096,
            Duration.ofDays(1),
            null);
        caches.add(backing);
        backing.save(KEY, SVG);
        Awaitility.await()
            .atMost(Duration.ofSeconds(3))
            .until(() -> backing.find(KEY) == null);
        assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void yamlMigrationsAreTrackedAndRepeatedStartupIsIdempotent() throws Exception {
        backing(4096)
            .save(KEY, SVG);
        long expiry = scalar("SELECT expires_at FROM permaformula_image_cache");
        assertEquals(5, scalar("SELECT count(*) FROM databasechangelog"));
        CacheSchema.migrate(source);
        assertEquals(5, scalar("SELECT count(*) FROM databasechangelog"));
        assertEquals(expiry, scalar("SELECT expires_at FROM permaformula_image_cache"));
        assertNotNull(backing(4096)
            .find(KEY));
    }

    @Test
    void incrementalChangesetsPreserveExistingDataAndMigrationHistory() throws Exception {
        backing(4096)
            .save(KEY, SVG);
        long expiry = scalar("SELECT expires_at FROM permaformula_image_cache");
        applyTestUpgrade(false);
        try {
            assertEquals(6, scalar("SELECT count(*) FROM databasechangelog"));
            assertEquals(1, scalar("SELECT count(*) FROM cache_migration_probe"));
            assertEquals(expiry, scalar("SELECT expires_at FROM permaformula_image_cache"));
            applyTestUpgrade(false);
            assertEquals(6, scalar("SELECT count(*) FROM databasechangelog"));
            assertEquals(1, scalar("SELECT count(*) FROM cache_migration_probe"));
        } finally {
            applyTestUpgrade(true);
        }
        CacheSchema.migrate(source);
        assertEquals(5, scalar("SELECT count(*) FROM databasechangelog"));
        assertNotNull(backing(4096)
            .find(KEY));
    }

    // Liquibase resources declare close() with broad checked exceptions, including interruption.
    @SuppressWarnings("try")
    private void applyTestUpgrade(boolean rollback) throws Exception {
        try (Connection connection = source.getConnection();
            ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor();
            Liquibase liquibase = new Liquibase("db/changelog/cache-upgrade-test.yaml", resources,
                new JdbcConnection(connection))) {
            if (rollback) {
                liquibase.rollback(1, "");
            } else {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    @Test
    void rejectsUntrackedLegacySchemaWithoutDeletingItsData() throws Exception {
        createLegacySchema(true);
        execute(
            """
            INSERT INTO permaformula_image_cache
                (cache_key, svg, size_bytes, created_at, last_access_at, expires_at)
            VALUES ('legacy', '<svg/>', 6, 1, 1, 2)
            """
        );
        try {
            assertThrows(LiquibaseException.class, () -> CacheSchema.migrate(source));
            assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
            assertEquals(0, scalar("SELECT count(*) FROM databasechangelog"));
        } finally {
            execute("DROP TABLE permaformula_image_cache");
            CacheSchema.migrate(source);
        }
    }

    @Test
    void libraryTracksSourcesBudgetAndExpiry() throws Exception {
        var cache = backing(4096);
        cache.save(KEY, SVG);
        assertTrue(cache.library(null, 12)
            .isEmpty());
        String descriptor = "{\"forumla\":\"x^2\"}";
        cache.describe(KEY, "formula", descriptor, "x^2");
        cache.describe(KEY, "formula", descriptor, "x^2");
        assertEquals(
            SVG.getBytes(StandardCharsets.UTF_8).length + descriptor.length()
                + 3
                + TestServices.validated(new StringCompressionService())
                    .compress(descriptor)
                    .length(),
            scalar("SELECT size_bytes FROM permaformula_image_cache"));
        assertEquals(descriptor, cache.libraryItem(KEY)
            .descriptor());
        assertEquals(cache.library(null, 12)
            .getFirst()
            .ubcq(),
            cache.libraryItem(KEY)
                .ubcq());
        assertEquals(descriptor, cache.library(null, 12)
            .getFirst()
            .descriptor());
        assertTrue(
            cache.library(new JdbcImageCache.LibraryCursor(Long.MIN_VALUE, KEY), 12)
                .isEmpty());
        execute("UPDATE permaformula_image_cache SET expires_at = 0");
        assertTrue(cache.library(null, 12)
            .isEmpty());
        assertNull(cache.libraryItem(KEY));
    }

    @Test
    void largeDescriptorsNeverEnterLibraryPagesAndFullDetailRemainsAvailable() throws Exception {
        var cache = backing(256L * 1024 * 1024);
        String original = "😀\n\"".repeat(200_000);
        String descriptor = new ObjectMapper()
            .writeValueAsString(
                Map.of("forumla", original, "style", "x".repeat(1024 * 1024)));
        for (int i = 1; i <= 24; i++) {
            String id = String.format("%064x", i);
            cache.save(id, SVG);
            cache.describe(id, "html", descriptor, original);
        }
        var items = cache.library(null, 24);
        assertEquals(24, items.size());
        for (var item : items) {
            assertEquals(256, item.excerpt()
                .codePointCount(0, item.excerpt()
                    .length()));
            assertNull(item.descriptor(), "Oversized page must never load full sources");
            assertFalse(
                Character.isHighSurrogate(item.excerpt()
                    .charAt(item.excerpt()
                        .length() - 1)));
        }
        var compression = TestServices.validated(new StringCompressionService());
        for (var item : items) {
            assertEquals(descriptor, compression.decompress(item.ubcq()));
        }
        assertEquals(descriptor, cache.libraryItem(items.getFirst()
            .id())
            .descriptor());

    }

    @Test
    void cursorUsesStableTieBreakAndSurvivesInsertionAndDeletedAnchor() throws Exception {
        var cache = backing(4096);
        for (int i = 1; i <= 4; i++) {
            String id = String.format("%064x", i);
            cache.save(id, SVG);
            cache.describe(id, "formula", "{\"forumla\":\"x\"}", "x");
        }
        execute("UPDATE permaformula_image_cache SET created_at = 1000");
        var first = cache.library(null, 2);
        assertEquals(String.format("%064x", 4),
            first.getFirst()
                .id());
        var anchor = first.get(1);
        cache.delete(anchor.id());
        cache.save(KEY, SVG);
        cache.describe(KEY, "formula", "{\"forumla\":\"new\"}", "new");
        var next = cache.library(new JdbcImageCache.LibraryCursor(anchor.createdAt(),
            anchor.id()),
            2);
        assertEquals(List.of(String.format("%064x", 2),
            String.format("%064x", 1)),
            next.stream()
                .map(JdbcImageCache.LibrarySummary::id)
                .toList());
    }

    @Test
    void inlineSourcesUseAggregateUtf8BudgetAndExcludeLookahead() throws Exception {
        var cache = backing(4L * 1024 * 1024);
        String prefix = "{\"forumla\":\"";
        String suffix = "\"}";
        int half = (int) JdbcImageCache.LIBRARY_SOURCE_BUDGET / 2;
        String descriptor = "%s%s%s".formatted(
            prefix,
            "x".repeat(half - prefix.length()
                - suffix.length()),
            suffix);
        String first = "b".repeat(64),
            second = "a".repeat(64),
            lookahead = "0".repeat(64);
        for (String id : List.of(first, second)) {
            cache.save(id, SVG);
            cache.describe(id, "formula", descriptor, "x");
        }
        cache.save(lookahead, SVG);
        cache.describe(lookahead, "html", "%s%s%s".formatted(prefix, "z".repeat(half * 3), suffix),
            "z");
        execute("UPDATE permaformula_image_cache SET created_at = 1000");
        var exact = cache.library(null, 2);
        assertEquals(3, exact.size());
        assertEquals(descriptor, exact.get(0)
            .descriptor());
        assertEquals(descriptor, exact.get(1)
            .descriptor());
        assertNull(exact.get(2)
            .descriptor(),
            "Lookahead never loads source or spends the page budget");
        cache.save(first, SVG);
        cache.describe(first, "formula",
            "%s%s%s".formatted(
                prefix,
                "x".repeat(half + 1 - prefix.length() - suffix.length()),
                suffix),
            "x");
        execute("UPDATE permaformula_image_cache SET created_at = 1000");
        assertTrue(cache.library(null, 2)
            .stream()
            .allMatch(item -> item.descriptor() == null),
            "One byte over the aggregate budget must switch the whole page to excerpts");
        execute("DELETE FROM permaformula_image_cache");
        String unicode = "%s%s%s".formatted(prefix, "😀".repeat(40_000), suffix);
        for (String id : List.of(first, second)) {
            cache.save(id, SVG);
            cache.describe(id, "formula", unicode, "😀");
        }
        assertTrue(unicode.length() * 2 < JdbcImageCache.LIBRARY_SOURCE_BUDGET);
        assertTrue(cache.library(null, 2)
            .stream()
            .allMatch(item -> item.descriptor() == null),
            "The budget is UTF-8 bytes, not Java characters");
    }

    @Test
    void preservesTrackedSchemaWithRenamedDescriptorBytes() throws Exception {
        backing(4096)
            .save(KEY, SVG);
        execute(
            """
            ALTER TABLE permaformula_image_cache RENAME COLUMN descriptor_bytes TO \
            legacy_descriptor_bytes\
            """
        );
        try {
            CacheSchema.migrate(source);
            assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
            assertEquals(5, scalar("SELECT count(*) FROM databasechangelog"));
            assertEquals(1, scalar(
                """
                SELECT count(*) FROM permaformula_image_cache \
                WHERE legacy_descriptor_bytes IS NULL\
                """
            ));
        } finally {
            execute(
                """
                ALTER TABLE permaformula_image_cache RENAME COLUMN legacy_descriptor_bytes TO \
                descriptor_bytes\
                """
            );
        }
    }

    @Test
    void tokenBudgetIncludesExactBoundaryAndKeepsCursorContinuity() throws Exception {
        var cache = backing(32L * 1024 * 1024);
        String a = String.format("%064x", 4),
            b = String.format("%064x", 3);
        String c = String.format("%064x", 2),
            d = String.format("%064x", 1);
        for (String id : List.of(a, b, c, d)) {
            cache.save(id, SVG);
            assertTrue(cache.describe(id, "html", "{\"forumla\":\"x\"}", "x"));
        }
        execute("UPDATE permaformula_image_cache SET created_at = 1000");
        token(a, 1024 * 1024);
        token(b, 1024 * 1024);
        token(c, 1);
        token(d, 2 * 1024 * 1024 + 1);
        // An excluded source must not suppress the visible page's full descriptors.
        execute(
            """
            UPDATE permaformula_image_cache SET descriptor_bytes = 9999999 \
            WHERE cache_key = '%s'\
            """
                .formatted(c));
        var first = cache.library(null, 12);
        assertEquals(List.of(a, b, c),
            first.stream()
                .map(JdbcImageCache.LibrarySummary::id)
                .toList());
        assertEquals(2, first.stream()
            .filter(row -> !row.lookahead())
            .count());
        assertTrue(first.subList(0, 2)
            .stream()
            .allMatch(row -> row.descriptor() != null));
        assertEquals(JdbcImageCache.LIBRARY_UBCQ_BUDGET, first.subList(0, 2)
            .stream()
            .mapToLong(row -> row.ubcq()
                .length())
            .sum());
        assertTrue(first.getLast()
            .lookahead());
        assertNull(first.getLast()
            .ubcq());
        assertNull(first.getLast()
            .descriptor());
        var second = cache.library(new JdbcImageCache.LibraryCursor(1000, b), 12);
        assertEquals(List.of(c, d),
            second.stream()
                .map(JdbcImageCache.LibrarySummary::id)
                .toList());
        assertFalse(second.getFirst()
            .lookahead());
        assertTrue(second.getLast()
            .lookahead());
        var last = cache.library(new JdbcImageCache.LibraryCursor(1000, c), 12);
        assertEquals(1, last.size());
        assertEquals(d, last.getFirst()
            .id());
        assertFalse(last.getFirst()
            .lookahead());
        assertEquals(2 * 1024 * 1024 + 1, last.getFirst()
            .ubcq()
            .length(),
            "Oversized token must remain complete and accessible");
        token(a, 2 * 1024 * 1024 + 1);
        token(b, 1);
        var oversizedFirst = cache.library(null, 12);
        assertEquals(2, oversizedFirst.size());
        assertEquals(a, oversizedFirst.getFirst()
            .id());
        assertFalse(oversizedFirst.getFirst()
            .lookahead());
        assertTrue(oversizedFirst.getLast()
            .lookahead());
        assertNull(oversizedFirst.getLast()
            .ubcq());
        var afterOversized = cache.library(new JdbcImageCache.LibraryCursor(1000, a), 12);
        assertEquals(List.of(b, c, d),
            afterOversized.stream()
                .map(JdbcImageCache.LibrarySummary::id)
                .toList());
    }

    @Test
    void missingTokenSizeFailsClosedAndPublicationReplacementUpdatesSize() throws Exception {
        var cache = backing(4096);
        String a = "b".repeat(64),
            b = "a".repeat(64);
        for (String id : List.of(a, b)) {
            cache.save(id, SVG);
            assertTrue(cache.describe(id, "html", "{\"forumla\":\"x\"}", "x"));
        }
        assertEquals(1, scalar(
            """
            SELECT count(*) FROM permaformula_image_cache WHERE cache_key='%s' AND \
            ubcq_bytes=length(ubcq)\
            """
                .formatted(a)));
        execute("UPDATE permaformula_image_cache SET created_at=1000, ubcq_bytes=NULL");
        var page = cache.library(null, 12);
        assertFalse(page.getFirst()
            .lookahead());
        assertNotNull(page.getFirst()
            .ubcq());
        assertTrue(page.getLast()
            .lookahead());
        assertNull(page.getLast()
            .ubcq());
        cache.save(a, SVG);
        assertEquals(-1,
            scalar(
                """
                SELECT coalesce(ubcq_bytes,-1) FROM permaformula_image_cache \
                WHERE cache_key='%s'\
                """
                    .formatted(a)));
        assertTrue(cache.describe(a, "html", "{\"forumla\":\"new\"}", "new"));
        assertEquals(1, scalar(
            """
            SELECT count(*) FROM permaformula_image_cache WHERE cache_key='%s' AND \
            ubcq_bytes=length(ubcq)\
            """
                .formatted(a)));
    }

    @Test
    void preservesTrackedSchemaWithRenamedUbcqBytes() throws Exception {
        backing(4096)
            .save(KEY, SVG);
        execute(
            "ALTER TABLE permaformula_image_cache RENAME COLUMN ubcq_bytes TO legacy_ubcq_bytes");
        try {
            CacheSchema.migrate(source);
            assertEquals(1, scalar("SELECT count(*) FROM permaformula_image_cache"));
            assertEquals(5, scalar("SELECT count(*) FROM databasechangelog"));
            assertEquals(1, scalar(
                "SELECT count(*) FROM permaformula_image_cache WHERE legacy_ubcq_bytes IS NULL"));
        } finally {
            execute(
                """
                ALTER TABLE permaformula_image_cache \
                RENAME COLUMN legacy_ubcq_bytes TO ubcq_bytes\
                """
            );
        }
    }

    /** Synthetic ASCII payloads isolate exact pagination boundaries from GZIP ratios. */
    private void token(String id, int size) throws Exception {
        try (var connection = source.getConnection();
            var update = connection.prepareStatement(
                "UPDATE permaformula_image_cache SET ubcq=?, ubcq_bytes=? WHERE cache_key=?")) {
            update.setString(1, "T".repeat(size));
            update.setInt(2, size);
            update.setString(3, id);
            assertEquals(1, update.executeUpdate());
        }
    }

    private void createLegacySchema(boolean expiryIndex) throws Exception {
        execute("DROP TABLE databasechangelog");
        execute("DROP TABLE databasechangeloglock");
        execute("DROP TABLE permaformula_image_cache");
        // Exact schema from the pre-Liquibase JDBC implementation, including its CHECK.
        execute(
            """
            CREATE TABLE permaformula_image_cache (
                cache_key varchar(64) PRIMARY KEY,
                svg text NOT NULL,
                size_bytes bigint NOT NULL CHECK (size_bytes > 0),
                created_at bigint NOT NULL,
                last_access_at bigint NOT NULL,
                expires_at bigint NOT NULL
            )
            """
        );
        execute(
            """
            CREATE INDEX permaformula_image_cache_access ON \
            permaformula_image_cache(last_access_at)\
            """
        );
        if (expiryIndex) {
            execute(
                """
                CREATE INDEX permaformula_image_cache_expiry ON \
                permaformula_image_cache(expires_at)\
                """
            );
        }
    }

    @Test
    void schemaRejectsNonPositivePayloadSizes() throws Exception {
        backing(4096)
            .save(KEY, SVG);
        assertThrows(SQLException.class,
            () -> execute("UPDATE permaformula_image_cache SET size_bytes = 0"));
    }

    @Test
    void invalidStoredOutputIsDiscardedAndRegenerated() throws Exception {
        var backing = backing(4096);
        backing.save(KEY, SVG);
        execute("UPDATE permaformula_image_cache SET svg = 'not XML'");
        assertNull(backing.find(KEY));
        assertEquals(0, scalar("SELECT count(*) FROM permaformula_image_cache"));
        assertEquals(SVG, front(backing)
            .getOrGenerate(KEY, () -> SVG));
    }

    @Test
    void policyVersionKeysDoNotReuseOldEntries() throws Exception {
        var backing = backing(4096);
        backing.save(KEY, SVG);
        var called = new AtomicInteger();
        front(backing)
            .getOrGenerate("b".repeat(64), () -> {
                called.incrementAndGet();
                return SVG;
            });
        assertEquals(1, called.get());
        assertEquals(2, scalar("SELECT count(*) FROM permaformula_image_cache"));
    }

    @Test
    void databaseOutageDoesNotDiscardSuccessfulMemoryOrRenderingResults() throws Exception {
        var store = front(backing(4096));
        store.getOrGenerate(KEY, () -> SVG);
        source.close();
        assertEquals(SVG, store.getOrGenerate(KEY, () -> {
            throw new AssertionError("Memory hit");
        }));
        assertEquals(SVG, store.getOrGenerate("new", () -> SVG));
    }
}
