package org.permmath.rendering.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.benmanes.caffeine.cache.Ticker;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.permmath.rendering.SvgOutputPolicy;
import org.springframework.util.unit.DataSize;

/** Same persistence/TTL contract is exercised against real SQLite and PostgreSQL. */
abstract class JdbcImageCacheContract {
    static final String KEY = "a".repeat(64);
    static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>first</text></svg>";
    static final Duration TTL = Duration.ofHours(1);
    HikariDataSource source;
    private final List<JdbcImageCache> caches = new ArrayList<>();
    abstract HikariDataSource openDatabase();
    abstract boolean sqlite();

    @BeforeEach void setUp() throws Exception {
        source = openDatabase();
        CacheSchema.migrate(source);
        backing(4096);
        execute("DELETE FROM permmath_image_cache");
    }

    JdbcImageCache backing(long bytes) throws Exception {
        var cache = new JdbcImageCache(source, sqlite(), TTL, bytes, Duration.ofDays(1), null);
        caches.add(cache);
        return cache;
    }

    CaffeineImageStore front(JdbcImageCache backing) {
        return new CaffeineImageStore(new ImageCacheProperties(DataSize.ofBytes(4096), TTL),
                Ticker.systemTicker(), backing);
    }

    @AfterEach void tearDown() throws Exception {
        for (var cache : caches) cache.close();
        if (source != null) source.close();
    }

    void execute(String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    long scalar(String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @Test
    void jpegSurvivesMemoryCacheReplacementAndCorruptJpegIsDiscarded() throws Exception {
        var image = new java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var output = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", output);
        String jpeg = org.permmath.rendering.JpegOutputPolicy.encode(output.toByteArray());
        var backing = backing(4096);
        assertEquals(jpeg, front(backing).getOrGenerate(KEY, () -> jpeg));
        assertEquals(jpeg, front(backing).getOrGenerate(KEY, () -> { throw new IOException("Must hit database"); }));
        backing.save(KEY, "jpeg-v1:bad-data");
        assertNull(backing.find(KEY));
        assertEquals(jpeg, front(backing).getOrGenerate(KEY, () -> jpeg));
    }

    @Test void survivesClosingAndReopeningTheDatabaseAndMemoryCache() throws Exception {
        var backing = backing(4096);
        front(backing).getOrGenerate(KEY, () -> SVG);
        backing.close();
        source.close();
        source = openDatabase();
        CacheSchema.migrate(source);
        var restarted = front(backing(4096));
        assertEquals(SvgOutputPolicy.sanitize(SVG), restarted.getOrGenerate(KEY, () -> {
            throw new AssertionError("Restart must load the persistent entry");
        }));
    }

    @Test void memoryHitsRefreshTheFullIdleTtlAndDoNotRewriteSvg() throws Exception {
        var store = front(backing(4096));
        store.getOrGenerate(KEY, () -> SVG);
        execute("UPDATE permmath_image_cache SET last_access_at = 1, expires_at = 2");
        assertEquals(SVG, store.getOrGenerate(KEY, () -> { throw new AssertionError("Memory hit"); }));
        assertEquals(TTL.toMillis(), scalar("SELECT expires_at - last_access_at FROM permmath_image_cache"));
        assertEquals(SVG.getBytes(StandardCharsets.UTF_8).length,
                scalar("SELECT size_bytes FROM permmath_image_cache"));
        assertNotNull(backing(4096).find(KEY));
    }

    @Test void expiredRowsAreNeverServedAndRegenerationReplacesTheirContent() throws Exception {
        var backing = backing(4096);
        front(backing).getOrGenerate(KEY, () -> SVG);
        execute("UPDATE permmath_image_cache SET expires_at = 0");
        assertNull(backing.find(KEY));
        String replacement = SVG.replace("first", "fresh");
        var restarted = front(backing(4096));
        assertEquals(replacement, restarted.getOrGenerate(KEY, () -> replacement));
        assertTrue(backing.find(KEY).contains("fresh"));
    }

    @Test void failuresAreNotPersistedAndRetryCanSucceed() throws Exception {
        var store = front(backing(4096));
        assertThrows(IOException.class, () -> store.getOrGenerate(KEY, () -> { throw new IOException("failed"); }));
        assertEquals(0, scalar("SELECT count(*) FROM permmath_image_cache"));
        assertEquals(SVG, store.getOrGenerate(KEY, () -> SVG));
        assertEquals(1, scalar("SELECT count(*) FROM permmath_image_cache"));
    }

    @Test void concurrentCallersStillShareOneGeneration() throws Exception {
        var store = front(backing(4096));
        var calls = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> store.getOrGenerate(KEY, () -> {
                calls.incrementAndGet(); return SVG;
            })));
            for (var result : results) assertEquals(SVG, result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get());
        assertEquals(1, scalar("SELECT count(*) FROM permmath_image_cache"));
    }

    @Test void cleanupRemovesExpiredAndLeastRecentlyAccessedEntriesWithinTheByteBudget() throws Exception {
        var backing = backing(SVG.getBytes(StandardCharsets.UTF_8).length * 2L);
        for (String key : List.of("old", "recent", "newest", "expired")) backing.save(key, SVG);
        execute("UPDATE permmath_image_cache SET last_access_at = 1 WHERE cache_key = 'old'");
        execute("UPDATE permmath_image_cache SET last_access_at = 2 WHERE cache_key = 'recent'");
        execute("UPDATE permmath_image_cache SET last_access_at = 3 WHERE cache_key = 'newest'");
        execute("UPDATE permmath_image_cache SET expires_at = 0 WHERE cache_key = 'expired'");
        backing.cleanUp();
        assertNull(backing.find("old"));
        assertNull(backing.find("expired"));
        assertNotNull(backing.find("recent"));
        assertNotNull(backing.find("newest"));
        assertEquals(2, scalar("SELECT count(*) FROM permmath_image_cache"));
    }

    @Test void oversizedOutputIsReturnedButNotPersisted() throws Exception {
        var store = front(backing(8));
        assertEquals(SVG, store.getOrGenerate(KEY, () -> SVG));
        assertEquals(0, scalar("SELECT count(*) FROM permmath_image_cache"));
    }

    @Test void persistentBudgetMeasuresUtf8BytesRatherThanJavaCharacters() throws Exception {
        String unicode = SVG.replace("first", "α");
        var store = front(backing(unicode.length()));
        assertEquals(unicode, store.getOrGenerate(KEY, () -> unicode));
        assertEquals(0, scalar("SELECT count(*) FROM permmath_image_cache"));
    }

    @Test void configuredIdleTtlExpiresWithoutCleanupOrAnApplicationRestart() throws Exception {
        var backing = new JdbcImageCache(source, sqlite(), Duration.ofMillis(150), 4096, Duration.ofDays(1), null);
        caches.add(backing);
        backing.save(KEY, SVG);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(3)).until(() -> backing.find(KEY) == null);
        assertEquals(1, scalar("SELECT count(*) FROM permmath_image_cache"));
    }

    @Test void yamlMigrationsAreTrackedAndRepeatedStartupIsIdempotent() throws Exception {
        backing(4096).save(KEY, SVG);
        long expiry = scalar("SELECT expires_at FROM permmath_image_cache");
        assertEquals(3, scalar("SELECT count(*) FROM databasechangelog"));
        CacheSchema.migrate(source);
        assertEquals(3, scalar("SELECT count(*) FROM databasechangelog"));
        assertEquals(expiry, scalar("SELECT expires_at FROM permmath_image_cache"));
        assertNotNull(backing(4096).find(KEY));
    }

    @Test void adoptsThePreviousJdbcSchemaWithoutDeletingDataOrResettingTtl() throws Exception {
        createLegacySchema(true);
        backing(4096).save(KEY, SVG);
        long expiry = scalar("SELECT expires_at FROM permmath_image_cache");
        CacheSchema.migrate(source);
        assertEquals(3, scalar("SELECT count(*) FROM databasechangelog WHERE exectype = 'MARK_RAN'"));
        assertEquals(expiry, scalar("SELECT expires_at FROM permmath_image_cache"));
        assertNotNull(backing(4096).find(KEY));
    }

    @Test void adoptsALegacyTableAndCreatesMissingIndexes() throws Exception {
        createLegacySchema(false);
        backing(4096).save(KEY, SVG);
        CacheSchema.migrate(source);
        assertEquals(2, scalar("SELECT count(*) FROM databasechangelog WHERE exectype = 'MARK_RAN'"));
        assertEquals(1, scalar("SELECT count(*) FROM databasechangelog WHERE exectype = 'EXECUTED'"));
        assertNotNull(backing(4096).find(KEY));
    }

    private void createLegacySchema(boolean expiryIndex) throws Exception {
        execute("DROP TABLE databasechangelog");
        execute("DROP TABLE databasechangeloglock");
        execute("DROP TABLE permmath_image_cache");
        // Exact schema from the pre-Liquibase JDBC implementation, including its CHECK.
        execute("""
            CREATE TABLE permmath_image_cache (
                cache_key varchar(64) PRIMARY KEY,
                svg text NOT NULL,
                size_bytes bigint NOT NULL CHECK (size_bytes > 0),
                created_at bigint NOT NULL,
                last_access_at bigint NOT NULL,
                expires_at bigint NOT NULL
            )
            """);
        execute("CREATE INDEX permmath_image_cache_access ON permmath_image_cache(last_access_at)");
        if (expiryIndex) execute("CREATE INDEX permmath_image_cache_expiry ON permmath_image_cache(expires_at)");
    }

    @Test void schemaRejectsNonPositivePayloadSizes() throws Exception {
        backing(4096).save(KEY, SVG);
        assertThrows(java.sql.SQLException.class, () -> execute("UPDATE permmath_image_cache SET size_bytes = 0"));
    }

    @Test void invalidStoredOutputIsDiscardedAndRegenerated() throws Exception {
        var backing = backing(4096);
        backing.save(KEY, SVG);
        execute("UPDATE permmath_image_cache SET svg = 'not XML'");
        assertNull(backing.find(KEY));
        assertEquals(0, scalar("SELECT count(*) FROM permmath_image_cache"));
        assertEquals(SVG, front(backing).getOrGenerate(KEY, () -> SVG));
    }

    @Test void policyVersionKeysDoNotReuseOldEntries() throws Exception {
        var backing = backing(4096);
        backing.save(KEY, SVG);
        var called = new AtomicInteger();
        front(backing).getOrGenerate("b".repeat(64), () -> { called.incrementAndGet(); return SVG; });
        assertEquals(1, called.get());
        assertEquals(2, scalar("SELECT count(*) FROM permmath_image_cache"));
    }

    @Test void databaseOutageDoesNotDiscardSuccessfulMemoryOrRenderingResults() throws Exception {
        var store = front(backing(4096));
        store.getOrGenerate(KEY, () -> SVG);
        source.close();
        assertEquals(SVG, store.getOrGenerate(KEY, () -> { throw new AssertionError("Memory hit"); }));
        assertEquals(SVG, store.getOrGenerate("new", () -> SVG));
    }
}
