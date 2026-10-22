package org.permaformula.rendering;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import jakarta.validation.ConstraintViolationException;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.permaformula.url.ImageDescriptor;

class StylesheetFetcherTests {

    @Test
    void externalLibrariesRetainGridExpansionLimits() {
        var fetcher = new StylesheetFetcher((uri, _) -> new StylesheetFetcher.Download(uri,
            "p{grid-template-columns:repeat(999999999,1fr)}"));
        assertThrows(ConstraintViolationException.class,
            () -> fetcher.fetch("https://example.com/theme.css"));
    }

    @Test
    void preservesBootstrapExternalStylesheetWithoutApplyingInlinePropertyAllowlist()
        throws Exception {
        String source;
        try (var input = getClass()
            .getResourceAsStream("/css/bootstrap-4.4.1.min.css")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var fetcher = new StylesheetFetcher(
            (uri, _) -> new StylesheetFetcher.Download(uri, source));
        String result = fetcher.fetch(
            "https://cdn.jsdelivr.net/npm/bootstrap@4.4.1/dist/css/bootstrap.min.css");
        assertTrue(result.contains(".container"));
        assertTrue(result.contains("data:image/svg+xml"));
        String document = RenderContentPolicy.document("<html><head></head><body></body></html>",
            new ImageDescriptor("<div class=\"container\">x</div>",
                500, null, null),
            List.of(result));
        assertTrue(document.contains("Content-Security-Policy"));
        assertTrue(document.contains("img-src 'none'"));
    }

    @Test
    void checksEveryRedirectIncludingRelativeLocationsAndDowngrades() throws Exception {
        var current = URI.create("https://example.com/css/main.css");
        try (var response = new BasicClassicHttpResponse(302)) {
            response.setHeader("Location", "theme.css");
            assertEquals("https://example.com/css/theme.css",
                StylesheetFetcher.redirect(current, response, 0)
                    .toString());
            assertThrows(ConstraintViolationException.class,
                () -> StylesheetFetcher.redirect(current,
                    response, StylesheetFetcher.MAX_REDIRECTS));
            assertAll(List.of("http://example.com/main.css", "https://127.0.0.1/x.css",
                "https://169.254.169.254/latest/", "https://[::1]/x.css",
                "https://localhost/x.css")
                .stream()
                .<Executable>map(location -> () -> {
                    response.setHeader("Location", location);
                    assertThrows(ConstraintViolationException.class,
                        () -> StylesheetFetcher.redirect(current, response, 0),
                        location);
                }));
        }
    }

    @Test
    void expandsRelativeImportsFromFinalRedirectDestinationAndKeepsOrder() throws Exception {
        var requested = Stream
            .<String>builder();
        var fetcher = new StylesheetFetcher((uri, _) -> {
            requested.add(uri.toString());
            if (uri.getPath()
                .equals("/main.css")) {
                return new StylesheetFetcher.Download(
                    URI.create("https://example.com/css/final.css"),
                    "@import 'base.css' screen; p {color:blue}");
            }
            return new StylesheetFetcher.Download(uri,
                "p {color:red}");
        });
        var css = fetcher.fetch("https://example.com/main.css");
        assertEquals(List.of("https://example.com/main.css", "https://example.com/css/base.css"),
            requested.build()
                .toList());
        assertTrue(css.contains("@media screen"));
        assertTrue(css.indexOf("color:red") < css.indexOf("color:blue"));
        assertFalse(css.contains("@import"));
        assertFalse(css.contains("evil.example"));
    }

    @Test
    void fetchesStylesheetsInOrderAndPreservesCheckedFailures() throws Exception {
        var requested = Stream
            .<String>builder();
        var refs = List.of("https://example.com/red.css", "https://example.com/blue.css");
        var fetcher = new StylesheetFetcher((uri, _) -> {
            requested.add(uri.toString());
            return new StylesheetFetcher.Download(uri,
                uri.getPath()
                    .equals("/red.css") ? "p{color:red}" : "p{color:blue}");
        });
        var stylesheets = fetcher.fetchAll(refs);
        assertEquals(refs, requested.build()
            .toList());
        assertEquals(2, stylesheets.size());
        assertTrue(stylesheets.get(0)
            .contains("color:red"));
        assertTrue(stylesheets.get(1)
            .contains("color:blue"));

        var failure = new IOException("download failed");
        var failing = new StylesheetFetcher((_, _) -> {
            throw failure;
        });
        assertSame(failure, assertThrows(IOException.class, () -> failing.fetchAll(refs)));
    }

    @Test
    void blocksUnsafeImportsCyclesAndExcessiveImportDepth() {
        var unsafe = new StylesheetFetcher((uri, _) -> new StylesheetFetcher.Download(uri,
            "@import 'https://127.0.0.1/private.css';"));
        assertThrows(ConstraintViolationException.class,
            () -> unsafe.fetch("https://example.com/main.css"));
        var cyclic = new StylesheetFetcher(
            (uri, _) -> new StylesheetFetcher.Download(uri, "@import 'main.css';"));
        assertThrows(ConstraintViolationException.class,
            () -> cyclic.fetch("https://example.com/main.css"));
        var deep = new StylesheetFetcher((uri, _) -> new StylesheetFetcher.Download(uri,
            "@import '%s/child.css';".formatted(uri.getPath())));
        assertThrows(ConstraintViolationException.class,
            () -> deep.fetch("https://example.com/main.css"));
    }

    @Test
    void rejectsUnsafeSchemesCredentialsAndPorts() {
        assertAll(List.of("http://example.com/x.css", "file:///tmp/x.css", "data:text/css,body{}",
            "https://user:pass@example.com/x.css", "https://example.com:8080/x.css",
            "https://example.com/x.css#fragment")
            .stream()
            .<Executable>map(url -> () -> {
                assertThrows(ConstraintViolationException.class,
                    () -> StylesheetFetcher.destination(url),
                    url);
            }));
        assertEquals("example.com",
            StylesheetFetcher.destination("https://example.com/x.css?v=1")
                .getHost());
    }

    @Test
    void blocksPrivateReservedAndTransitionAddresses() throws Exception {
        assertAll(List.of("0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.169.254", "172.16.0.1",
            "192.168.1.1", "100.64.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fe80::1",
            "ff02::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2001:db8::1", "2001::1",
            "2002:7f00:1::1", "3fff::1")
            .stream()
            .<Executable>map(value -> () -> {
                var address = InetAddress.getByName(value);
                assertFalse(StylesheetFetcher.publicAddress(address), value);
                assertThrows(UnknownHostException.class,
                    () -> StylesheetFetcher.PublicDnsResolver
                        .validate(new InetAddress[] { address }),
                    value);
            }));
        assertTrue(StylesheetFetcher.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(StylesheetFetcher.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void rejectsMixedDnsAnswersAndUsesValidatedAddressSnapshot() throws Exception {
        var publicAddress = InetAddress.getByName("8.8.8.8");
        var snapshot = new InetAddress[] { publicAddress };
        assertSame(snapshot, StylesheetFetcher.PublicDnsResolver.validate(snapshot));
        assertThrows(UnknownHostException.class, () -> StylesheetFetcher.PublicDnsResolver.validate(
            new InetAddress[] { publicAddress, InetAddress.getByName("127.0.0.1") }));
        assertThrows(UnknownHostException.class,
            () -> new StylesheetFetcher.PublicDnsResolver()
                .resolve("localhost"));
    }

    @Test
    void acceptsCssAndRejectsWrongTypesCompressedResponsesAndErrors() throws Exception {
        try (var response = response("body {color:red}", "text/css; charset=UTF-8")) {
            assertEquals("body {color:red}", StylesheetFetcher.readCss(response));
        }
        try (var response = response("<script>alert(1)</script>", "text/html")) {
            assertThrows(ConstraintViolationException.class,
                () -> StylesheetFetcher.readCss(response));
        }
        try (var response = response("body{}", "text/css")) {
            response.addHeader("Content-Encoding", "gzip");
            assertThrows(ConstraintViolationException.class,
                () -> StylesheetFetcher.readCss(response));
        }
        try (var response = new BasicClassicHttpResponse(404)) {
            assertThrows(ConstraintViolationException.class,
                () -> StylesheetFetcher.readCss(response));
        }
    }

    @Test
    void boundsResponseWhileStreamingEvenWithoutContentLength() throws Exception {
        try (var response = new BasicClassicHttpResponse(200)) {
            response.setEntity(new InputStreamEntity(
                new ByteArrayInputStream(new byte[StylesheetFetcher.MAX_BYTES + 1]),
                -1, ContentType.create("text/css")));
            assertThrows(ConstraintViolationException.class,
                () -> StylesheetFetcher.readCss(response));
        }
        assertThrows(ConstraintViolationException.class, () -> new StylesheetFetcher()
            .fetchAll(
                Collections.nCopies(StylesheetFetcher.MAX_REFS + 1, "https://example.com/x.css")));
    }

    private static BasicClassicHttpResponse response(String body, String type) {
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity(body, ContentType.parse(type)));
        return response;
    }
}
