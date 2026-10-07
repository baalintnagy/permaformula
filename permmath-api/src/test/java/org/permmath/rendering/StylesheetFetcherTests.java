package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class StylesheetFetcherTests {

    @Test
    void checksEveryRedirectIncludingRelativeLocationsAndDowngrades() throws Exception {
        var current = URI.create("https://example.com/css/main.css");
        try (var response = new BasicClassicHttpResponse(302)) {
            response.setHeader("Location", "theme.css");
            assertEquals("https://example.com/css/theme.css", StylesheetFetcher.redirect(current, response, 0).toString());
            assertThrows(IllegalArgumentException.class, () -> StylesheetFetcher.redirect(current, response, StylesheetFetcher.MAX_REDIRECTS));
            assertAll(List.of("http://example.com/main.css", "https://127.0.0.1/x.css",
                    "https://169.254.169.254/latest/", "https://[::1]/x.css", "https://localhost/x.css").stream()
                .<Executable>map(location -> () -> {
                    response.setHeader("Location", location);
                    assertThrows(IllegalArgumentException.class, () -> StylesheetFetcher.redirect(current, response, 0), location);
                }));
        }
    }

    @Test
    void expandsRelativeImportsFromFinalRedirectDestinationAndKeepsOrder() throws Exception {
        var requested = Stream.<String>builder();
        var fetcher = new StylesheetFetcher((uri, _) -> {
            requested.add(uri.toString());
            if (uri.getPath().equals("/main.css")) {
                return new StylesheetFetcher.Download(URI.create("https://example.com/css/final.css"), "@import 'base.css' screen; p {color:blue}");
            }
            return new StylesheetFetcher.Download(uri, "p {color:red; background-image:url(https://evil.example/x)}");
        });
        var css = fetcher.fetch("https://example.com/main.css");
        assertEquals(List.of("https://example.com/main.css", "https://example.com/css/base.css"),
            requested.build().toList());
        assertTrue(css.contains("@media screen"));
        assertTrue(css.indexOf("color:red") < css.indexOf("color:blue"));
        assertFalse(css.contains("@import"));
        assertFalse(css.contains("evil.example"));
    }

    @Test
    void fetchesStylesheetsInOrderAndPreservesCheckedFailures() throws Exception {
        var requested = Stream.<String>builder();
        var refs = List.of("https://example.com/red.css", "https://example.com/blue.css");
        var fetcher = new StylesheetFetcher((uri, _) -> {
            requested.add(uri.toString());
            return new StylesheetFetcher.Download(uri,
                uri.getPath().equals("/red.css") ? "p{color:red}" : "p{color:blue}");
        });
        var stylesheets = fetcher.fetchAll(refs);
        assertEquals(refs, requested.build().toList());
        assertEquals(2, stylesheets.size());
        assertTrue(stylesheets.get(0).contains("color:red"));
        assertTrue(stylesheets.get(1).contains("color:blue"));

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
        assertThrows(IllegalArgumentException.class, () -> unsafe.fetch("https://example.com/main.css"));
        var cyclic = new StylesheetFetcher((uri, _) -> new StylesheetFetcher.Download(uri, "@import 'main.css';"));
        assertThrows(IllegalArgumentException.class, () -> cyclic.fetch("https://example.com/main.css"));
        var deep = new StylesheetFetcher((uri, _) -> new StylesheetFetcher.Download(uri,
                "@import '" + uri.getPath() + "/child.css';"));
        assertThrows(IllegalArgumentException.class, () -> deep.fetch("https://example.com/main.css"));
    }

    @Test
    void rejectsUnsafeSchemesCredentialsAndPorts() {
        assertAll(List.of("http://example.com/x.css", "file:///tmp/x.css", "data:text/css,body{}",
                "https://user:pass@example.com/x.css", "https://example.com:8080/x.css", "https://example.com/x.css#fragment").stream()
            .<Executable>map(url -> () -> {
                assertThrows(IllegalArgumentException.class, () -> StylesheetFetcher.destination(url), url);
            }));
        assertEquals("example.com", StylesheetFetcher.destination("https://example.com/x.css?v=1").getHost());
    }

    @Test
    void blocksPrivateReservedAndTransitionAddresses() throws Exception {
        assertAll(List.of("0.0.0.0", "10.1.2.3", "127.0.0.1", "169.254.169.254", "172.16.0.1",
                "192.168.1.1", "100.64.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1",
                "203.0.113.1", "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fe80::1",
                "ff02::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1", "2001:db8::1", "2001::1", "2002:7f00:1::1", "3fff::1").stream()
            .<Executable>map(value -> () -> {
                var address = InetAddress.getByName(value);
                assertFalse(StylesheetFetcher.publicAddress(address), value);
                assertThrows(UnknownHostException.class,
                        () -> StylesheetFetcher.PublicDnsResolver.validate(new InetAddress[]{address}), value);
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
                new InetAddress[]{publicAddress, InetAddress.getByName("127.0.0.1")}));
        assertThrows(UnknownHostException.class, () -> new StylesheetFetcher.PublicDnsResolver().resolve("localhost"));
    }

    @Test
    void acceptsCssAndRejectsWrongTypesCompressedResponsesAndErrors() throws Exception {
        try (var response = response("body {color:red}", "text/css; charset=UTF-8")) {
            assertEquals("body {color:red}", StylesheetFetcher.readCss(response));
        }
        try (var response = response("<script>alert(1)</script>", "text/html")) {
            assertThrows(IllegalArgumentException.class, () -> StylesheetFetcher.readCss(response));
        }
        try (var response = response("body{}", "text/css")) {
            response.addHeader("Content-Encoding", "gzip");
            assertThrows(IllegalArgumentException.class, () -> StylesheetFetcher.readCss(response));
        }
        try (var response = new BasicClassicHttpResponse(404)) {
            assertThrows(IllegalArgumentException.class, () -> StylesheetFetcher.readCss(response));
        }
    }

    @Test
    void boundsResponseWhileStreamingEvenWithoutContentLength() throws Exception {
        try (var response = new BasicClassicHttpResponse(200)) {
            response.setEntity(new InputStreamEntity(new ByteArrayInputStream(new byte[StylesheetFetcher.MAX_BYTES + 1]),
                    -1, ContentType.create("text/css")));
            assertThrows(IllegalArgumentException.class, () -> StylesheetFetcher.readCss(response));
        }
        assertThrows(IllegalArgumentException.class, () -> new StylesheetFetcher().fetchAll(
                Collections.nCopies(StylesheetFetcher.MAX_REFS + 1, "https://example.com/x.css")));
    }

    private static BasicClassicHttpResponse response(String body, String type) {
        var response = new BasicClassicHttpResponse(200);
        response.setEntity(new StringEntity(body, ContentType.parse(type)));
        return response;
    }
}
