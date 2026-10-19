package org.permaformula;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.SocketFactory;

import com.sun.management.ThreadMXBean;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.permaformula.TestServices;
import org.permaformula.rendering.*;
import org.permaformula.rendering.ApiWorkProperties;
import org.permaformula.rendering.ApiWorkService;
import org.permaformula.rendering.StylesheetFetcher;
import org.permaformula.rendering.cache.*;
import org.permaformula.url.ImageDescriptor;
import org.permaformula.validation.InputLimits;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in experiment against a disposable PostgreSQL database. Never use production. */
@EnabledIfSystemProperty(named = "ubcq.measure", matches = "true")
class UbcqLibraryMeasurementTests {
    static final AtomicLong received = new AtomicLong();
    static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>ok</text></svg>";
    final JsonMapper mapper = JsonMapper.builder().build();
    final ThreadMXBean allocations = (ThreadMXBean) ManagementFactory
        .getThreadMXBean();

    /** Count actual PostgreSQL protocol bytes received by this measurement JVM. */
    public static class CountingFactory extends SocketFactory {
        @Override
        public Socket createSocket() {
            return new Socket() {
                InputStream counted;

                @Override
                public synchronized InputStream getInputStream() throws IOException {
                    if (counted == null) {
                        counted = new FilterInputStream(super.getInputStream()) {
                            @Override
                            public int read() throws IOException {
                                int value = in.read();
                                if (value >= 0) {
                                    received.incrementAndGet();
                                }
                                return value;
                            }

                            @Override
                            public int read(byte[] bytes, int offset, int length)
                                throws IOException {
                                int count = in.read(bytes, offset, length);
                                if (count > 0) {
                                    received.addAndGet(count);
                                }
                                return count;
                            }
                        };
                    }
                    return counted;
                }
            };
        }

        @Override
        public Socket createSocket(String h, int p) throws IOException {
            var s = createSocket();
            s.connect(new InetSocketAddress(h, p));
            return s;
        }

        @Override
        public Socket createSocket(InetAddress h, int p) throws IOException {
            return createSocket(h.getHostAddress(), p);
        }

        @Override
        public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException {
            var s = createSocket();
            s.bind(new InetSocketAddress(l, lp));
            s.connect(new InetSocketAddress(h, p));
            return s;
        }

        @Override
        public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException {
            return createSocket(h.getHostAddress(), p, l, lp);
        }
    }

    @Test
    void measure() throws Exception {
        assertEquals("yes", System.getProperty("ubcq.measure.disposable"),
            "Explicit disposable-database acknowledgement required");
        String url = System.getProperty("ubcq.measure.url",
            "jdbc:postgresql://127.0.0.1:55439/permaformula");
        assertTrue(url.startsWith("jdbc:postgresql://127.0.0.1:"),
            "Disposable loopback database only");
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("permaformula");
        config.setPassword(System.getenv("PERMAFORMULA_POSTGRES_TEST_PASSWORD"));
        config.addDataSourceProperty("sslmode", "disable");
        config.addDataSourceProperty("socketFactory", CountingFactory.class.getName());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(2);
        System.out.printf("MEASURE environment java=%s maxHeap=%d processors=%d%n",
            System.getProperty("java.version"), Runtime.getRuntime().maxMemory(),
            Runtime.getRuntime().availableProcessors());
        try (var source = new HikariDataSource(config);
            var cache = new JdbcImageCache(source, false, Duration.ofHours(1), 2L << 30,
                Duration.ofDays(1), null)) {
            CacheSchema.migrate(source);
            var factory = new DefaultListableBeanFactory();
            factory.registerSingleton("cache", cache);
            var controller = new LibraryController(factory.getBeanProvider(JdbcImageCache.class),
                mapper);
            // Warm controller/Jackson/JDBC paths before the measured cases.
            controller.list(null, 0, 12);
            mapper.writeValue(OutputStream.nullOutputStream(),
                new LibraryController.Page(List.of(), null));
            for (String scenario : List.of("ordinary", "repetitive-max", "random-max")) {
                populate(source, cache, scenario);
                try (var c = source.getConnection();
                    var q = c.createStatement();
                    var r = q.executeQuery(
                        """
                            SELECT octet_length(descriptor),octet_length(ubcq),pg_column_size(ubcq) FROM permaformula_image_cache LIMIT 1\
                            """)) {
                    assertTrue(r.next());
                    System.out.printf(
                        "MEASURE fixture=%s descriptorBytes=%d tokenBytes=%d pgStoredTokenBytes=%d%n",
                        scenario, r.getLong(1), r.getLong(2), r.getLong(3));
                }
                for (int limit : List.of(12, 24)) {
                    System.gc();
                    Thread.sleep(100);
                    long baseline = heap(), wire = received.get(), allocated = allocated(),
                        start = System.nanoTime();
                    var peak = new AtomicLong(baseline);
                    var running = new AtomicBoolean(true);
                    var sampler = Thread.ofPlatform().daemon().start(() -> {
                        while (running.get()) {
                            peak.accumulateAndGet(heap(), Math::max);
                            try {
                                Thread.sleep(2);
                            } catch (InterruptedException _) {
                                return;
                            }
                        }
                    });
                    long bytes;
                    long tokenBytes;
                    int fullSources;
                    int returnedRows;
                    try {
                        var page = controller.list(null, 0, limit).getBody();
                        assertNotNull(page);
                        tokenBytes = page.items().stream()
                            .mapToLong(i -> i.ubcq().length())
                            .sum();
                        returnedRows = page.items().size();
                        assertTrue(tokenBytes <= JdbcImageCache.LIBRARY_UBCQ_BUDGET
                            || returnedRows == 1);
                        fullSources = (int) page.items().stream()
                            .filter(i -> i.descriptor() != null)
                            .count();
                        var output = new Counter();
                        mapper.writeValue(output, page);
                        bytes = output.bytes;
                        peak.accumulateAndGet(heap(), Math::max);
                    } finally {
                        running.set(false);
                        sampler.join();
                    }
                    System.out.printf(
                        """
                            MEASURE list=%s requested=%d rows=%d tokenBytes=%d responseBytes=%d \
                            dbWireBytes=%d allocatedBytes=%d sampledHeapDelta=%d elapsedMs=%.1f \
                            fullSources=%d%n\
                            """,
                        scenario, limit, returnedRows, tokenBytes, bytes, received.get() - wire,
                        allocated() - allocated, peak.get() - baseline,
                        (System.nanoTime() - start) / 1e6, fullSources);
                }
                if (scenario.equals("random-max")) {
                    blocked(controller, 12);
                    blocked(controller, 24);
                }
            }
        }
    }

    void populate(HikariDataSource source, JdbcImageCache cache, String scenario) throws Exception {
        try (var c = source.getConnection(); var q = c.createStatement()) {
            q.execute("TRUNCATE permaformula_image_cache");
        }
        int htmlBytes = scenario.equals("ordinary") ? 4096 : InputLimits.MAX_CONTENT_BYTES;
        int cssBytes = scenario.equals("ordinary") ? 0 : InputLimits.MAX_STYLE_BYTES;
        boolean random = scenario.equals("random-max");
        String html = "<!--" + content(htmlBytes - 16, random, 123) + "--><p>ok</p>";
        String css = cssBytes == 0 ? "" : "/*" + content(cssBytes - 4, random, 456) + "*/";
        if (scenario.equals("ordinary")) {
            html = """
                <!--sample--><div class="math-card"><span class="eyebrow">MATHEMATICAL \
                IDENTITY</span><h1>The sum of cubes</h1><p>The sum has a closed form:</p><div \
                class="equation">`sum_(i=1)^n i^3 = ((n(n+1))/2)^2`</div><p class="caption">The \
                sum of the first n cubes equals the square of the n-th triangular number.</p></div>\
                """;
            css = """
                body { margin:0; padding:0; } .math-card { padding:48px; background:#edf3fb; \
                color:#263b54; font-family:Georgia,serif; } .eyebrow { font:11px Arial,sans-serif; \
                letter-spacing:2px; color:#496f9f; } h1 { font-size:36px; margin:18px 0; } p { \
                line-height:1.7; } .equation { padding:24px 0; font-size:25px; } .caption { \
                color:#687d96; font-size:14px; }\
                """;
        }
        var descriptor = new ImageDescriptor(html, 500, List.of(), css);
        var preparedBytes = new AtomicInteger();
        // Exercise the real input policies. A stub records the already-sanitized
        // document: this measures listing, not Chromium throughput or output correctness.
        var gateway = new RenderGateway() {
            public byte[] renderHtml(String document, int width) {
                preparedBytes.set(document.getBytes(StandardCharsets.UTF_8).length);
                return SVG.getBytes(StandardCharsets.UTF_8);
            }

            public byte[] renderFormula(String formula) {
                throw new UnsupportedOperationException();
            }
        };
        try (var renderer = TestServices.validated(new ImageRendererService(
            new PermaFormulaProperties("http://localhost", 500, 2000), gateway,
            new ApiWorkService(ApiWorkProperties.defaults()), new StylesheetFetcher()))) {
            renderer.getImage(descriptor).getInputStream().close();
        }
        assertTrue(preparedBytes.get() < 65536,
            "Large comments must leave a small prepared document");
        System.out.printf("MEASURE accepted=%s htmlBytes=%d cssBytes=%d preparedDocumentBytes=%d%n",
            scenario, html.length(), css.length(), preparedBytes.get());
        for (int i = 0; i < 24; i++) {
            String key = String.format("%064x", i + 1);
            // Distinct descriptors avoid an unrealistic identical-token page.
            var unique = new ImageDescriptor(
                html.substring(0, 4) + String.format("%02d", i) + html.substring(6), 500, List.of(),
                css);
            // Keep the exact maximum-size fixture within the accepted input ceiling.
            if (unique.forumla().length() > InputLimits.MAX_CONTENT_BYTES) {
                throw new AssertionError("Fixture too large");
            }
            String json = mapper.writeValueAsString(unique);
            boolean published = false;
            for (int attempt = 0; attempt < 4
                && !published; attempt++) {
                cache.saveIfAbsent(key, SVG);
                published = cache.describe(key, "html", json, "ok");
                if (!published) {
                    System.out.printf("MEASURE setupRetry=%s row=%d attempt=%d%n", scenario, i,
                        attempt + 1);
                    Thread.sleep(500);
                }
            }
            assertTrue(published, "Fixture publication must eventually succeed");
        }
    }

    static String content(int length, boolean random, long seed) {
        char[] chars = new char[length];
        var rng = new Random(seed);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < length; i++) {
            chars[i] = random ? alphabet.charAt(rng.nextInt(alphabet.length())) : 'a';
        }
        return new String(chars);
    }

    void blocked(LibraryController controller, int limit) throws Exception {
        System.gc();
        Thread.sleep(100);
        long baseline = heap();
        var interceptor = new LibraryAdmissionConfiguration.Admission();
        var handler = new HandlerMethod(controller,
            LibraryController.class.getMethod("list", String.class, int.class, int.class));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var actualRows = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Long>>();
            for (int i = 0; i < 2; i++) {
                futures.add(workers.submit(() -> {
                    var req = new MockHttpServletRequest();
                    var res = new MockHttpServletResponse();
                    assertTrue(interceptor.preHandle(req, res, handler));
                    try {
                        var page = controller.list(null, 0, limit).getBody();
                        actualRows.addAndGet(page.items().size());
                        var output = new Counter() {
                            boolean held;

                            @Override
                            public void write(byte[] b, int o, int n) throws IOException {
                                super.write(b, o, n);
                                if (!held) {
                                    held = true;
                                    entered.countDown();
                                    try {
                                        if (!release.await(15, TimeUnit.SECONDS)) {
                                            throw new IOException("Hold timeout");
                                        }
                                    } catch (InterruptedException e) {
                                        throw new IOException(e);
                                    }
                                }
                            }
                        };
                        mapper.writeValue(output, page);
                        return output.bytes;
                    } finally {
                        interceptor.afterCompletion(req, res, handler, null);
                    }
                }));
            }
            try {
                if (!entered.await(8, TimeUnit.SECONDS)) {
                    release.countDown();
                    int failures = 0;
                    for (var future : futures) {
                        try {
                            future.get(20, TimeUnit.SECONDS);
                        } catch (ExecutionException failure) {
                            failures++;
                            Throwable cause = failure.getCause();
                            System.out.printf(
                                "MEASURE concurrentReadFailure rowsEach=%d type=%s sqlState=%s%n",
                                limit,
                                cause.getClass().getSimpleName(),
                                cause instanceof SQLException sql ? sql.getSQLState()
                                    : "none");
                        }
                    }
                    assertTrue(failures > 0,
                        "Missing writers must be explained by a failed operation");
                    System.out.printf(
                        "MEASURE heldNotReached rowsEach=%d failedReads=%d (production deadlines unchanged)%n",
                        limit, failures);
                    return;
                }
                System.gc();
                Thread.sleep(150);
                long retained = heap() - baseline;
                var denial = assertThrows(ResponseStatusException.class,
                    () -> interceptor.preHandle(new MockHttpServletRequest(),
                        new MockHttpServletResponse(), handler));
                assertEquals(429, denial.getStatusCode().value());
                Thread.sleep(1000);
                System.out.printf(
                    """
                        MEASURE heldResponses=2 requestedRowsEach=%d actualRowsTotal=%d \
                        retainedHeapDelta=%d thirdStatus=429 holdMs=1000%n\
                        """,
                    limit, actualRows.get(), retained);
            } finally {
                release.countDown();
            }
            for (var f : futures) {
                assertTrue(f.get(20, TimeUnit.SECONDS) > 0);
            }
            var recovered = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(recovered, response, handler));
            interceptor.afterCompletion(recovered, response, handler, null);
        }
    }

    long allocated() {
        return allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    static class Counter extends OutputStream {
        long bytes;

        @Override
        public void write(int b) throws IOException {
            bytes++;
        }

        @Override
        public void write(byte[] b, int offset, int length) throws IOException {
            bytes += length;
        }
    }
}
