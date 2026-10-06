package org.permmath.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class ClientRateLimitsTests {
    private final AtomicLong now = new AtomicLong();

    private ClientRateLimits limits(int maximumClients, Duration expiry, List<String> proxies) {
        return new ClientRateLimits(new ClientRateLimitProperties(60, 2, 32, 60, 2, 1,
                maximumClients, expiry, proxies), now::get);
    }

    static MockHttpServletRequest request(String ip) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        return request;
    }

    @Test void ignoresForgedHeadersFromUntrustedPeersAndCanonicalizesAddresses() {
        var limits = limits(10, Duration.ofMinutes(1), List.of("172.30.86.2"));
        var direct = request("203.0.113.1");
        direct.addHeader("X-Forwarded-For", "198.51.100.1");
        direct.addHeader("Forwarded", "for=198.51.100.2");
        direct.addHeader("X-Real-IP", "198.51.100.3");
        assertEquals("203.0.113.1", limits.identity(direct));
        var proxy = request("172.30.86.2");
        proxy.addHeader("X-Forwarded-For", "2001:0db8:0:0:0:0:0:1");
        assertEquals("2001:db8::1", limits.identity(proxy));
        assertEquals("203.0.113.1", limits.identity(request("::ffff:203.0.113.1")));
    }

    @Test void rejectsMissingAmbiguousOrNonLiteralTrustedProxyHeadersWithoutDns() {
        var limits = limits(10, Duration.ofMinutes(1), List.of("172.30.86.2"));
        for (String value : List.of("example.com", "127.0.0.1, 203.0.113.1", "203.0.113.1:80", "[::1]", " 203.0.113.1")) {
            var proxy = request("172.30.86.2");
            proxy.addHeader("X-Forwarded-For", value);
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> limits.open(proxy)).getStatusCode().value());
        }
        assertThrows(ResponseStatusException.class, () -> limits.open(request("172.30.86.2")));
        var duplicate = request("172.30.86.2");
        duplicate.addHeader("X-Forwarded-For", "203.0.113.1");
        duplicate.addHeader("X-Forwarded-For", "203.0.113.2");
        assertThrows(ResponseStatusException.class, () -> limits.open(duplicate));
    }

    @Test void isolatesClientsAndRefillsUsingMonotonicTime() {
        var limits = limits(10, Duration.ofMinutes(1), List.of());
        var first = request("203.0.113.1");
        try (var scope = limits.open(first)) { }
        try (var scope = limits.open(first)) { }
        var denied = assertThrows(ClientRateLimits.Limited.class, () -> limits.open(first));
        assertEquals("1", denied.getHeaders().getFirst("Retry-After"));
        try (var scope = limits.open(request("203.0.113.2"))) { }
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        try (var scope = limits.open(first)) { }
        assertThrows(ClientRateLimits.Limited.class, () -> limits.open(first));
    }

    @Test void rejectsNewIdentitiesAtCapacityWithoutResettingExistingAllowances() {
        var limits = limits(1, Duration.ofSeconds(2), List.of());
        var first = request("203.0.113.1");
        try (var scope = limits.open(first)) { }
        assertThrows(ClientRateLimits.Limited.class, () -> limits.open(request("203.0.113.2")));
        try (var scope = limits.open(first)) { }
        assertThrows(ClientRateLimits.Limited.class, () -> limits.open(first));
        now.addAndGet(Duration.ofSeconds(3).toNanos());
        try (var scope = limits.open(request("203.0.113.2"))) { }
    }

    @Test void pinsActiveRequestsBeyondIdleExpiry() {
        var limits = limits(1, Duration.ofSeconds(2), List.of());
        try (var scope = limits.open(request("203.0.113.1"))) {
            now.addAndGet(Duration.ofSeconds(3).toNanos());
            assertThrows(ClientRateLimits.Limited.class, () -> limits.open(request("203.0.113.2")));
        }
        now.addAndGet(Duration.ofSeconds(3).toNanos());
        try (var scope = limits.open(request("203.0.113.2"))) { }
    }

    @Test void retainsWorkerConcurrencyAfterRequestClosesAndReleasesOnActualExit() throws Exception {
        var limits = limits(2, Duration.ofSeconds(2), List.of());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<String> active;
            try (var scope = limits.open(request("203.0.113.1"))) {
                var operation = ClientRateLimits.generation(() -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
                    return "done";
                });
                active = executor.submit(operation::run);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
            }
            try {
                now.addAndGet(Duration.ofSeconds(3).toNanos());
                try (var scope = limits.open(request("203.0.113.1"))) {
                    var second = ClientRateLimits.generation(() -> "second");
                    assertEquals(429, assertThrows(ClientRateLimits.Limited.class, second::run).getStatusCode().value());
                }
                try (var scope = limits.open(request("203.0.113.2"))) {
                    assertEquals("other", ClientRateLimits.generation(() -> "other").run());
                }
            } finally { release.countDown(); }
            assertEquals("done", active.get(2, TimeUnit.SECONDS));
            now.addAndGet(Duration.ofSeconds(1).toNanos());
            try (var scope = limits.open(request("203.0.113.1"))) {
                assertEquals("recovered", ClientRateLimits.generation(() -> "recovered").run());
            }
        }
    }

    @Test void failedGenerationsSpendTokensAndReleaseConcurrency() throws Exception {
        var limits = limits(10, Duration.ofMinutes(1), List.of());
        try (var scope = limits.open(request("203.0.113.1"))) {
            assertThrows(java.io.IOException.class, () -> ClientRateLimits.generation(() -> {
                throw new java.io.IOException("failed");
            }).run());
            assertEquals("ok", ClientRateLimits.generation(() -> "ok").run());
            assertThrows(ClientRateLimits.Limited.class, () -> ClientRateLimits.generation(() -> "denied"));
        }
        assertEquals("internal", ClientRateLimits.generation(() -> "internal").run());
    }

    @Test void rejectsExpiryThatWouldGrantFreshTokensBeforeFullRefillAndHostnames() {
        assertThrows(IllegalArgumentException.class, () -> limits(10, Duration.ofSeconds(1), List.of()));
        assertThrows(IllegalArgumentException.class, () -> limits(10, Duration.ofMinutes(1), List.of("ingress")));
    }

    @Test void sharedInFlightCacheLoadDoesNotSpendAnotherClientsGenerationTokens() throws Exception {
        var limits = new ClientRateLimits(new ClientRateLimitProperties(60, 10, 32, 1, 1, 1,
                10, Duration.ofMinutes(1), List.of()), now::get);
        var store = new org.permmath.rendering.cache.CaffeineImageStore(
                new org.permmath.rendering.cache.ImageCacheProperties(
                        org.springframework.util.unit.DataSize.ofMegabytes(1), Duration.ofHours(1)));
        var work = new org.permmath.rendering.ApiWorkService(org.permmath.rendering.ApiWorkProperties.defaults());
        var entered = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                try (var scope = limits.open(request("203.0.113.1"))) {
                    return store.getOrGenerate("shared", () -> work.generate(ClientRateLimits.generation(() -> {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException interrupted) { throw new java.io.IOException(interrupted); }
                        return "shared-svg";
                    })));
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                try (var scope = limits.open(request("203.0.113.2"))) {
                    waiting.countDown();
                    String shared = store.getOrGenerate("shared", () -> {
                        fail("Duplicate caller must reuse the in-flight load");
                        return "wrong";
                    });
                    assertEquals("still-available", work.generate(ClientRateLimits.generation(() -> "still-available")));
                    return shared;
                }
            });
            try {
                assertTrue(waiting.await(2, TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(50, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            assertEquals("shared-svg", first.get(2, TimeUnit.SECONDS));
            assertEquals("shared-svg", second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test void boundsConcurrentRequestsIncludingSharedImageWaitersAndReleasesOnClose() {
        var limits = new ClientRateLimits(new ClientRateLimitProperties(60, 10, 1, 60, 2, 1,
                10, Duration.ofMinutes(1), List.of()), now::get);
        try (var scope = limits.open(request("203.0.113.1"))) {
            assertThrows(ClientRateLimits.Limited.class, () -> limits.open(request("203.0.113.1")));
            try (var other = limits.open(request("203.0.113.2"))) { }
        }
        try (var scope = limits.open(request("203.0.113.1"))) { }
    }
}
