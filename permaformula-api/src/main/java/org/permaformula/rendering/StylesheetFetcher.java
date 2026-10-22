package org.permaformula.rendering;

import static org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create;
import static org.permaformula.validation.QuickValidator.notNull;
import static org.permaformula.validation.QuickValidator.validate;
import static org.permaformula.validation.QuickValidator.violation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.writer.CSSWriter;
import com.helger.css.writer.CSSWriterSettings;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.permaformula.validation.ValidationText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fetches CSS outside Chromium, with validated DNS used by the actual connection.
 */
@Component
public final class StylesheetFetcher {

    static final int MAX_BYTES = 1024 * 1024;

    static final int MAX_REFS = 8;

    static final int MAX_REDIRECTS = 3;

    static final int MAX_RESOURCES = 16;

    static final int MAX_IMPORT_DEPTH = 3;

    record Download(URI uri, String css) {
    }

    @FunctionalInterface
    interface Downloader {

        Download get(URI uri, long deadline) throws IOException;
    }

    private final Downloader downloader;
    private final StylesheetFetchProperties properties;

    public StylesheetFetcher() {
        this(StylesheetFetchProperties.direct());
    }

    @Autowired
    StylesheetFetcher(StylesheetFetchProperties properties) {
        this.properties = properties;
        downloader = this::download;
    }

    StylesheetFetcher(Downloader downloader) {
        this.properties = StylesheetFetchProperties.direct();
        this.downloader = downloader;
    }

    private static final class Budget {

        final long deadline = System.nanoTime()
            + WorkDeadline.remainingNanos(Duration.ofSeconds(15));

        int resources;

        int bytes;
    }

    List<String> fetchAll(List<String> refs) throws IOException {
        if (refs == null) {
            return List.of();
        }
        validate(
            !(refs.size() > MAX_REFS),
            "Too many stylesheet references (maximum 8)");
        Budget budget = new Budget();
        try {
            return refs.stream()
                .map(ref -> {
                    try {
                        return expand(destination(ref), budget, new HashSet<>(), 0);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                })
                .toList();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    String fetch(String ref) throws IOException {
        return expand(destination(ref), new Budget(), new HashSet<>(), 0);
    }

    private String expand(URI uri, Budget budget, Set<URI> ancestors, int depth)
        throws IOException {
        checkDeadline(budget.deadline);
        validate(
            !(depth > MAX_IMPORT_DEPTH
                || ++budget.resources > MAX_RESOURCES),
            "Stylesheet import limit exceeded");
        validate(
            ancestors.add(uri),
            "Cyclic stylesheet imports");
        try {
            Download downloaded = downloader.get(uri, budget.deadline);
            checkDeadline(budget.deadline);
            budget.bytes += ValidationText.utf8Length(downloaded.css());
            validate(
                !(budget.bytes > 4 * MAX_BYTES),
                "Combined stylesheets exceed 4 MiB");
            CascadingStyleSheet sheet = StylesheetPolicy.parse(downloaded.css());
            StringBuilder output = new StringBuilder();
            CSSWriterSettings writer = new CSSWriterSettings(true);
            for (var imported : sheet.getAllImportRules()) {
                URI location = destination(
                    downloaded.uri()
                        .resolve(imported.getLocationString())
                        .toString());
                String css = expand(location, budget, ancestors, depth + 1);
                if (imported.hasMediaQueries()) {
                    output.append("@media ")
                        .append(imported.getAllMediaQueries()
                            .stream()
                            .map(query -> query.getAsCSSString(writer, 0))
                            .collect(Collectors.joining(",")))
                        .append('{')
                        .append(css)
                        .append('}');
                } else {
                    output.append(css);
                }
            }
            sheet.removeAllImportRules();
            output.append(new CSSWriter(writer)
                .getCSSAsString(sheet));
            String result = StylesheetPolicy.externalStylesheet(output.toString());
            checkDeadline(budget.deadline);
            return result;
        } finally {
            ancestors.remove(uri);
        }
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline
            || Thread.currentThread()
                .isInterrupted()) {
            throw WorkDeadline.timedOut();
        }
    }

    private Download download(URI uri, long deadline) throws IOException {
        try (ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform()
                .daemon(true)
                .name("stylesheet-deadline")
                .factory())) {
            for (int redirects = 0;; redirects++) {
                // Proxied targets are resolved/validated by Squid at the actual connection
                // boundary.
                // The isolated API only resolves its trusted proxy, not public destination hosts.
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw WorkDeadline.timedOut();
                }
                PoolingHttpClientConnectionManager manager = create()
                    .setDnsResolver(properties.proxy() == null ? new PublicDnsResolver()
                        : SystemDefaultDnsResolver.INSTANCE)
                    .setDefaultConnectionConfig(
                        ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(5))
                            .setSocketTimeout(Timeout.ofSeconds(5))
                            .build())
                    .build();
                HttpClientBuilder clientBuilder = HttpClients.custom()
                    .setConnectionManager(manager)
                    .disableRedirectHandling()
                    .disableAutomaticRetries()
                    .disableCookieManagement()
                    .disableContentCompression()
                    .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(5))
                        .build());
                if (properties.proxy() != null) {
                    clientBuilder.setRoutePlanner(
                        new DefaultProxyRoutePlanner(
                            HttpHost.create(properties.proxy())));
                }
                try (CloseableHttpClient client = clientBuilder.build()) {
                    HttpGet request = new HttpGet(uri);
                    ScheduledFuture<?> cancellation = timer.schedule(() -> {
                        request.cancel();
                        // Stop slow response bodies as well as connections.
                        client.close(CloseMode.IMMEDIATE);
                    }, remaining, TimeUnit.NANOSECONDS);
                    request.setHeader("Accept", "text/css");
                    request.setHeader("Accept-Encoding", "identity");
                    try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
                        try {
                            int status = response.getCode();
                            if (List.of(301, 302, 303, 307, 308)
                                .contains(status)) {
                                uri = redirect(uri, response, redirects);
                                continue;
                            }
                            return new Download(uri, readCss(response));
                        } finally {
                            // Close the connection BEFORE closing the entity. Otherwise HTTP stream
                            // cleanup
                            // can drain an unbounded redirect, error or oversized body for
                            // connection reuse.
                            client.close(CloseMode.IMMEDIATE);
                        }
                    } finally {
                        cancellation.cancel(false);
                    }
                }
            }
        } catch (IOException failure) {
            checkDeadline(deadline);
            throw failure;
        }
    }

    static URI destination(String ref) {
        try {
            URI uri = URI.create(ref);
            validate(
                !(!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1
                        && uri.getPort() != 443)
                    || ref.length() > 4096),
                "Stylesheets must use HTTPS on port 443 without credentials or fragments");
            String host = uri.getHost()
                .toLowerCase(Locale.ROOT);
            validate(
                !(host.equals("localhost")
                    || host.endsWith(".localhost")),
                "Stylesheet destination must be public");
            if (host.contains(":")
                || host.matches("[0-9.]+")) {
                try {
                    validate(
                        !(!publicAddress(InetAddress.getByName(host))),
                        "Stylesheet destination must be public");
                } catch (UnknownHostException invalid) {
                    throw new IllegalArgumentException("Invalid stylesheet address", invalid);
                }
            }
            return uri;
        } catch (IllegalArgumentException _) {
            throw violation("Invalid stylesheet URL");
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("Missing stylesheet URL", e);
        }
    }

    static URI redirect(URI current, ClassicHttpResponse response, int redirects) {
        Header location = notNull(response.getFirstHeader("Location"),
            "Invalid stylesheet redirect chain");
        validate(
            redirects < MAX_REDIRECTS,
            "Invalid stylesheet redirect chain");
        return destination(current.resolve(location.getValue())
            .toString());
    }

    static String readCss(ClassicHttpResponse response) throws IOException {
        validate(
            !(response.getCode() != 200
                || response.getEntity() == null),
            "Stylesheet request did not return CSS (HTTP %s)".formatted(response.getCode()));
        validate(
            !(response.containsHeader("Content-Encoding")
                && !"identity".equalsIgnoreCase(response.getFirstHeader("Content-Encoding")
                    .getValue())),
            "Compressed stylesheet responses are not supported");
        HttpEntity entity = response.getEntity();
        ContentType type = notNull(ContentType.parse(entity.getContentType()),
            "Stylesheet response must have Content-Type text/css");
        validate(
            "text/css".equalsIgnoreCase(type.getMimeType()),
            "Stylesheet response must have Content-Type text/css");
        validate(
            !(entity.getContentLength() > MAX_BYTES),
            "Stylesheet exceeds 1 MiB");
        // Caller closes the connection and response; closing an HTTP stream here may drain its
        // remainder.
        byte[] bytes = entity.getContent()
            .readNBytes(MAX_BYTES + 1);
        validate(
            !(bytes.length > MAX_BYTES),
            "Stylesheet exceeds 1 MiB");
        return new String(bytes, type.getCharset(StandardCharsets.UTF_8));
    }

    static final class PublicDnsResolver implements DnsResolver {

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return validate(InetAddress.getAllByName(host));
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }

        static InetAddress[] validate(InetAddress[] addresses) throws UnknownHostException {
            if (addresses.length == 0) {
                throw new UnknownHostException("Stylesheet destination has no addresses");
            }
            if (!Arrays.stream(addresses)
                .allMatch(StylesheetFetcher::publicAddress)) {
                throw new UnknownHostException("Stylesheet destination is not a public address");
            }
            return addresses;
        }
    }

    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255,
                c = b[1] & 255,
                d = b[2] & 255;
            return !(a == 0
                || a == 10
                || a == 127
                || a >= 224
                || (a == 100
                    && c >= 64
                    && c <= 127)
                || (a == 169
                    && c == 254)
                || (a == 172
                    && c >= 16
                    && c <= 31)
                || (a == 192
                    && c == 168)
                || (a == 192
                    && c == 0
                    && (d == 0
                        || d == 2))
                || (a == 192
                    && c == 88
                    && d == 99)
                || (a == 198
                    && (c == 18
                        || c == 19))
                || (a == 198
                    && c == 51
                    && d == 100)
                || (a == 203
                    && c == 0
                    && d == 113));
        }
        // Only native global unicast IPv6; exclude transition and special-purpose ranges.
        return (b[0] & 0xe0) == 0x20
            && !((b[0] & 255) == 0x20
                && (b[1] & 255) == 0x02)
            && !((b[0] & 255) == 0x20
                && (b[1] & 255) == 0x01
                && (b[2] & 255) < 2)
            && !((b[0] & 255) == 0x20
                && (b[1] & 255) == 0x01
                && (b[2] & 255) == 0x0d
                && (b[3] & 255) == 0xb8)
            && !((b[0] & 255) == 0x3f
                && (b[1] & 255) == 0xff
                && (b[2] & 0xf0) == 0);
    }
}
