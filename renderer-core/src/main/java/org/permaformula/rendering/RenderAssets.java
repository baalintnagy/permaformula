package org.permaformula.rendering;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;

/** Loopback-only asset server so both engines load the same bundled MathJax. */
final class RenderAssets implements AutoCloseable {
    private final HttpServer server;
    private final Map<String, String> documents = new ConcurrentHashMap<>();

    RenderAssets() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("X-DNS-Prefetch-Control", "off");
            String path = exchange.getRequestURI().getPath();
            byte[] bytes;
            if (path.startsWith("/document/")) {
                String html = documents.get(path);
                bytes = html == null ? null : html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            } else if ((path.startsWith("/mathjax/")
                || path.equals("/export-svg.js"))
                && !path.contains("..")) {
                try (InputStream input = RenderAssets.class.getResourceAsStream(path)) {
                    bytes = input == null ? null : input.readAllBytes();
                }
                exchange.getResponseHeaders().set("Content-Type", path.endsWith(".js")
                    ? "application/javascript"
                    : "application/octet-stream");
            } else {
                bytes = null;
            }
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    String add(String html) {
        String path = "/document/" + UUID.randomUUID();
        documents.put(path, html);
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    void remove(String url) {
        documents.remove(URI.create(url).getPath());
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
