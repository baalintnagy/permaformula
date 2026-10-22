package org.permaformula.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.permaformula.AdminLoginController;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoginAdmissionTests {
    private final AtomicLong now = new AtomicLong();
    private final ClientRateLimits limits = new ClientRateLimits(new ClientRateLimitProperties(
        600, 120, 32, 60, 10, 1, 100, Duration.ofSeconds(12),
        List.of("172.30.90.2")),
        now::get);

    @Test
    void rejectedHttpAttemptsNeverReachAuthenticationAndRecover() throws Exception {
        var auth = mock(AuthenticationManager.class);
        var encoder = mock(JwtEncoder.class);
        when(auth.authenticate(any()))
            .thenThrow(new BadCredentialsException("bad"));
        var mvc = MockMvcBuilders.standaloneSetup(new AdminLoginController(auth, encoder, limits))
            .build();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/admin/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status()
                    .isUnauthorized());
        }
        mvc.perform(post("/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", "198.51.100.99")
            .content("{\"username\":\"another-name\",\"password\":\"wrong\"}"))
            .andExpect(status()
                .isTooManyRequests())
            .andExpect(header()
                .string("Retry-After", "12"))
            .andExpect(header()
                .string("Cache-Control", "no-store"));
        verify(auth, times(3))
            .authenticate(any());
        verifyNoInteractions(encoder);
        now.addAndGet(Duration.ofSeconds(12)
            .toNanos());
        mvc.perform(post("/admin/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status()
                .isUnauthorized());
        verify(auth, times(4))
            .authenticate(any());
    }

    @Test
    void globalBudgetStopsRotatingIpsAndDoesNotBlockOrdinaryRequests() {
        for (int i = 1; i <= 3; i++) {
            assertEquals("ok",
                limits.authenticate(ClientRateLimitsTests.request("203.0.113.%s".formatted(i)),
                    () -> "ok"));
        }
        var denied = assertThrows(ClientRateLimits.Limited.class, () -> limits.authenticate(
            ClientRateLimitsTests.request("203.0.113.4"),
            () -> fail("Verification must not execute")));
        assertEquals("6", denied.getHeaders()
            .getFirst("Retry-After"));
        try (var scope = limits.open(ClientRateLimitsTests.request("203.0.113.4"))) {
            assertNotNull(scope);
        }
        now.addAndGet(Duration.ofSeconds(6)
            .toNanos());
        assertEquals("ok",
            limits.authenticate(ClientRateLimitsTests.request("203.0.113.4"), () -> "ok"));
    }

    @Test
    void busyVerificationFailsFastAndReleasesSlotAfterFailure() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = workers.submit(
                () -> limits.authenticate(ClientRateLimitsTests.request("203.0.113.1"), () -> {
                    entered.countDown();
                    try {
                        assertTrue(finish.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        throw new AssertionError(failure);
                    }
                    throw new BadCredentialsException("bad");
                }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var rejected = workers.submit(() -> assertThrows(ClientRateLimits.Limited.class,
                    () -> limits.authenticate(ClientRateLimitsTests.request("203.0.113.2"),
                        () -> fail("Busy verification must not execute"))));
                assertEquals("1",
                    rejected.get(1, TimeUnit.SECONDS)
                        .getHeaders()
                        .getFirst("Retry-After"));
                try (var scope = limits.open(ClientRateLimitsTests.request("203.0.113.2"))) {
                    assertNotNull(scope);
                }
            } finally {
                finish.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> running.get(5, TimeUnit.SECONDS));
        }
        assertEquals("ok",
            limits.authenticate(ClientRateLimitsTests.request("203.0.113.2"), () -> "ok"));
    }

    @Test
    void shortClientExpiryCannotResetLoginAllowanceAndProxyIdentityIsReused() {
        var request = ClientRateLimitsTests.request("172.30.90.2");
        request.addHeader("X-Forwarded-For", "203.0.113.1");
        for (int i = 0; i < 3; i++) {
            limits.authenticate(request, () -> true);
        }
        now.addAndGet(Duration.ofSeconds(13)
            .toNanos());
        limits.authenticate(ClientRateLimitsTests.request("203.0.113.1"), () -> true);
        assertThrows(ClientRateLimits.Limited.class,
            () -> limits.authenticate(request, () -> fail("No reset")));
    }
}
