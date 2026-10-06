package org.permmath.rendering;

import static org.junit.jupiter.api.Assertions.*;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

class StylesheetProxyTests {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StylesheetFetchProperties.class)
    static class Settings { }

    @Test void bindsRequiredProxyAndFailsStartupWithoutIt() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Settings.class);
        runner.withPropertyValues("permmath.stylesheet-fetch.proxy-required=true").run(context ->
                assertNotNull(context.getStartupFailure()));
        runner.withPropertyValues("permmath.stylesheet-fetch.proxy-required=true",
                "permmath.stylesheet-fetch.proxy=http://stylesheet-egress:3128").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(StylesheetFetchProperties.class).proxyRequired());
                });
        for (String value : java.util.List.of("https://proxy:3128", "http://user:pass@proxy:3128",
                "http://proxy", "http://proxy:3128/path", "http://proxy:3128?x=1")) {
            assertThrows(IllegalArgumentException.class, () -> new StylesheetFetchProperties(true, URI.create(value)));
        }
    }

    @Test void forcesHttpsConnectThroughProxyAndDoesNotFallbackOnDenial() throws Exception {
        try (var listener = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.setSoTimeout(5000);
            var connection = tasks.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    while (!reader.readLine().isEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return request;
                }
            });
            var properties = new StylesheetFetchProperties(true, URI.create("http://localhost:" + listener.getLocalPort()));
            assertTimeout(Duration.ofSeconds(7), () -> assertThrows(Exception.class,
                    () -> new StylesheetFetcher(properties).fetch("https://stylesheet.invalid/test.css")));
            assertEquals("CONNECT stylesheet.invalid:443 HTTP/1.1", connection.get(5, TimeUnit.SECONDS));
        }
    }

    @Test void unreachableProxyFailsInsteadOfUsingDirectInternet() throws Exception {
        int port;
        try (var listener = new ServerSocket(0)) { port = listener.getLocalPort(); }
        var properties = new StylesheetFetchProperties(true, URI.create("http://localhost:" + port));
        assertThrows(java.io.IOException.class, () -> new StylesheetFetcher(properties).fetch("https://8.8.8.8/test.css"));
    }
}
