package org.permmath.rendering;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.helger.css.writer.CSSWriter;
import com.helger.css.writer.CSSWriterSettings;

/**
 * Fetches CSS outside Chromium, with validated DNS used by the actual connection.
 */
@Component
final class StylesheetFetcher {

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

    StylesheetFetcher() {
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

        final long deadline = System.nanoTime() + WorkDeadline.remainingNanos(Duration.ofSeconds(15));

        int resources;

        int bytes;
    }

    List<String> fetchAll(List<String> refs) throws IOException {
        if (refs == null) {
            return List.of();
        }
        if (refs.size() > MAX_REFS) {
            throw new IllegalArgumentException("Too many stylesheet references (maximum 8)");
        }
        var result = new ArrayList<String>();
        var budget = new Budget();
        for (var ref : refs) {
            result.add(expand(destination(ref), budget, new HashSet<>(), 0));
        }
        return result;
    }

    String fetch(String ref) throws IOException {
        return expand(destination(ref), new Budget(), new HashSet<>(), 0);
    }

    private String expand(URI uri, Budget budget, Set<URI> ancestors, int depth) throws IOException {
        checkDeadline(budget.deadline);
        if (depth > MAX_IMPORT_DEPTH || ++budget.resources > MAX_RESOURCES) {
            throw new IllegalArgumentException("Stylesheet import limit exceeded");
        }
        if (!ancestors.add(uri)) {
            throw new IllegalArgumentException("Cyclic stylesheet imports");
        }
        try {
            var downloaded = downloader.get(uri, budget.deadline);
            checkDeadline(budget.deadline);
            budget.bytes += downloaded.css().getBytes(StandardCharsets.UTF_8).length;
            if (budget.bytes > 4 * MAX_BYTES) {
                throw new IllegalArgumentException("Combined stylesheets exceed 4 MiB");
            }
            var sheet = StylesheetPolicy.parse(downloaded.css());
            var output = new StringBuilder();
            var writer = new CSSWriterSettings(true);
            for (var imported : sheet.getAllImportRules()) {
                var location = destination(downloaded.uri().resolve(imported.getLocationString()).toString());
                var css = expand(location, budget, ancestors, depth + 1);
                if (imported.hasMediaQueries()) {
                    output.append("@media ").append(imported.getAllMediaQueries().stream()
                            .map(query -> query.getAsCSSString(writer, 0)).collect(Collectors.joining(",")))
                            .append('{').append(css).append('}');
                } else {
                    output.append(css);
                }
            }
            sheet.removeAllImportRules();
            output.append(new CSSWriter(writer).getCSSAsString(sheet));
            var result = StylesheetPolicy.stylesheet(output.toString());
            checkDeadline(budget.deadline);
            return result;
        } finally {
            ancestors.remove(uri);
        }
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) {
            throw WorkDeadline.timedOut();
        }
    }

    private Download download(URI uri, long deadline) throws IOException {
        try (var timer = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("stylesheet-deadline").factory())) {
            for (int redirects = 0; ; redirects++) {
                // Proxied targets are resolved/validated by Squid at the actual connection boundary.
                // The isolated API only resolves its trusted proxy, not public destination hosts.
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw WorkDeadline.timedOut();
                }
                var manager = PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(properties.proxy() == null ? new PublicDnsResolver()
                                : SystemDefaultDnsResolver.INSTANCE)
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(5))
                                .setSocketTimeout(Timeout.ofSeconds(5)).build()).build();
                var clientBuilder = HttpClients.custom().setConnectionManager(manager)
                        .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                        .disableContentCompression().setDefaultRequestConfig(RequestConfig.custom()
                                .setResponseTimeout(Timeout.ofSeconds(5)).build());
                if (properties.proxy() != null) clientBuilder.setRoutePlanner(
                        new DefaultProxyRoutePlanner(
                                HttpHost.create(properties.proxy())));
                try (var client = clientBuilder.build()) {
                    var request = new HttpGet(uri);
                    var cancellation = timer.schedule(() -> {
                        request.cancel();
                        // Stop slow response bodies as well as connections.
                        client.close(CloseMode.IMMEDIATE);
                    }, remaining, TimeUnit.NANOSECONDS);
                    request.setHeader("Accept", "text/css");
                    request.setHeader("Accept-Encoding", "identity");
                    try (var response = client.executeOpen(null, request, null)) {
                        try {
                            int status = response.getCode();
                            if (List.of(301, 302, 303, 307, 308).contains(status)) {
                                uri = redirect(uri, response, redirects);
                                continue;
                            }
                            return new Download(uri, readCss(response));
                        } finally {
                            // Close the connection BEFORE closing the entity. Otherwise HTTP stream cleanup
                            // can drain an unbounded redirect, error or oversized body for connection reuse.
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
            var uri = URI.create(ref);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || ref.length() > 4096) {
                throw new IllegalArgumentException("Stylesheets must use HTTPS on port 443 without credentials or fragments");
            }
            var host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("localhost") || host.endsWith(".localhost")) {
                throw new IllegalArgumentException("Stylesheet destination must be public");
            }
            if (host.contains(":") || host.matches("[0-9.]+")) {
                try {
                    if (!publicAddress(InetAddress.getByName(host))) {
                        throw new IllegalArgumentException("Stylesheet destination must be public");
                    }
                } catch (UnknownHostException invalid) {
                    throw new IllegalArgumentException("Invalid stylesheet address", invalid);
                }
            }
            return uri;
        } catch (NullPointerException e) {
            throw new IllegalArgumentException("Missing stylesheet URL", e);
        }
    }

    static URI redirect(URI current, ClassicHttpResponse response, int redirects) {
        var location = response.getFirstHeader("Location");
        if (redirects >= MAX_REDIRECTS || location == null) {
            throw new IllegalArgumentException("Invalid stylesheet redirect chain");
        }
        return destination(current.resolve(location.getValue()).toString());
    }

    static String readCss(ClassicHttpResponse response) throws IOException {
        if (response.getCode() != 200 || response.getEntity() == null) {
            throw new IllegalArgumentException("Stylesheet request did not return CSS (HTTP " + response.getCode() + ")");
        }
        if (response.containsHeader("Content-Encoding")
                && !"identity".equalsIgnoreCase(response.getFirstHeader("Content-Encoding").getValue())) {
            throw new IllegalArgumentException("Compressed stylesheet responses are not supported");
        }
        var entity = response.getEntity();
        var type = ContentType.parse(entity.getContentType());
        if (type == null || !"text/css".equalsIgnoreCase(type.getMimeType())) {
            throw new IllegalArgumentException("Stylesheet response must have Content-Type text/css");
        }
        if (entity.getContentLength() > MAX_BYTES) {
            throw new IllegalArgumentException("Stylesheet exceeds 1 MiB");
        }
        // Caller closes the connection and response; closing an HTTP stream here may drain its remainder.
        var bytes = entity.getContent().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Stylesheet exceeds 1 MiB");
        }
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
            if (!Arrays.stream(addresses).allMatch(StylesheetFetcher::publicAddress)) {
                throw new UnknownHostException("Stylesheet destination is not a public address");
            }
            return addresses;
        }
    }

    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && c >= 64 && c <= 127) || (a == 169 && c == 254)
                    || (a == 172 && c >= 16 && c <= 31) || (a == 192 && c == 168)
                    || (a == 192 && c == 0 && (d == 0 || d == 2)) || (a == 192 && c == 88 && d == 99)
                    || (a == 198 && (c == 18 || c == 19)) || (a == 198 && c == 51 && d == 100)
                    || (a == 203 && c == 0 && d == 113));
        }
        // Only native global unicast IPv6; exclude transition and special-purpose ranges.
        return (b[0] & 0xe0) == 0x20
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x02)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) < 2)
                && !((b[0] & 255) == 0x20 && (b[1] & 255) == 0x01 && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)
                && !((b[0] & 255) == 0x3f && (b[1] & 255) == 0xff && (b[2] & 0xf0) == 0);
    }
}
