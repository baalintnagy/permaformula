package org.permaformula.ratelimit;

import java.time.Duration;
import java.util.Enumeration;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.net.InetAddresses;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.http.HttpServletRequest;
import org.permaformula.rendering.ApiWorkService.Operation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** In-memory per-instance quotas. Expiration never resets a partially refilled bucket. */
@Component
public final class ClientRateLimits {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private final ClientRateLimitProperties properties;
    private final Cache<String, Client> clients;
    private final TimeMeter clock;
    private final Semaphore loginSlot = new Semaphore(1);
    private final Bucket loginAttempts;

    @Autowired
    public ClientRateLimits(ClientRateLimitProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    ClientRateLimits(ClientRateLimitProperties properties, Ticker ticker) {
        this.properties = properties;
        clients = Caffeine.newBuilder()
            .expireAfter(new Expiry<String, Client>() {
                private long duration(Client client) {
                    // An interrupted request can leave a non-cooperative worker running.
                    // Pin its identity until the worker actually exits, rather than resetting its
                    // concurrency allowance.
                    return client.leases > 0 ? Long.MAX_VALUE
                        : Math.max(properties.expireAfterAccess()
                            .toNanos(),
                            client.logins != null ? Duration.ofSeconds(36)
                                .toNanos() : 0);
                }

                @Override
                public long expireAfterCreate(String key, Client value, long now) {
                    return duration(value);
                }

                @Override
                public long expireAfterUpdate(String key, Client value, long now, long remaining) {
                    return duration(value);
                }

                @Override
                public long expireAfterRead(String key, Client value, long now, long remaining) {
                    return duration(value);
                }

            })
            .ticker(ticker)
            .build();
        clock = new TimeMeter() {
            @Override
            public long currentTimeNanos() {
                return ticker.read();
            }

            @Override
            public boolean isWallClockBased() {
                return false;
            }
        };
        loginAttempts = bucket(10, 3);
    }

    String identity(HttpServletRequest request) {
        String peer = canonical(request.getRemoteAddr());
        if (properties.trustedProxies()
            .contains(peer)) {
            Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
            if (!headers.hasMoreElements()) {
                throw invalidProxyHeader();
            }
            String forwarded = headers.nextElement();
            if (headers.hasMoreElements()
                || !InetAddresses.isInetAddress(forwarded)) {
                throw invalidProxyHeader();
            }
            return canonical(forwarded);
        }
        return peer;
    }

    private static String canonical(String value) {
        return InetAddresses.toAddrString(InetAddresses.forString(value));
    }

    private static ResponseStatusException invalidProxyHeader() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Trusted proxy must supply one literal client IP");
    }

    // Serialize admission only; never evict a live client to make room for a fresh allowance.
    private synchronized Client client(String identity) {
        Client client = clients.getIfPresent(identity);
        if (client != null) {
            return client;
        }
        clients.cleanUp();
        if (clients.estimatedSize() >= properties.maximumClients()) {
            throw new Limited("Client rate-limit capacity reached",
                properties.expireAfterAccess()
                    .toSeconds() + 1);
        }
        client = new Client(identity,
            bucket(properties.requestsPerMinute(),
                properties.requestBurst()),
            bucket(properties.generationsPerMinute(),
                properties.generationBurst()),
            new Semaphore(properties.generationConcurrency()));
        clients.put(identity, client);
        return client;
    }

    private Bucket bucket(long rate, long burst) {
        return Bucket.builder()
            .withCustomTimePrecision(clock)
            .addLimit(limit -> limit.capacity(burst)
                .refillGreedy(rate, Duration.ofMinutes(1)))
            .build();
    }

    /** Limit all usernames together; never queue expensive password verification. */
    public <T> T authenticate(HttpServletRequest request, Supplier<T> verification) {
        Client client;
        synchronized (this) {
            client = client(identity(request));
            if (client.logins == null) {
                client.logins = bucket(5, 3);
            }
            clients.put(client.identity, client);
            consume(client.logins, "Login rate exceeded");
            if (!loginSlot.tryAcquire()) {
                throw new Limited("Login verification busy", 1);
            }
            try {
                consume(loginAttempts, "Login rate exceeded");
            } catch (RuntimeException failure) {
                loginSlot.release();
                throw failure;
            }
            retain(client);
        }
        try {
            return verification.get();
        } finally {
            loginSlot.release();
            release(client);
        }
    }

    public synchronized Scope open(HttpServletRequest request) {
        Client client = client(identity(request));
        consume(client.requests(), "Request rate exceeded");
        if (client.requestsActive >= properties.requestConcurrency()) {
            throw new Limited("Client request concurrency exceeded", 1);
        }
        client.requestsActive++;
        retain(client);
        Context previous = CURRENT.get();
        CURRENT.set(new Context(this, client));
        return new Scope(this, client, previous);
    }

    /**
     * Called only by the cache's actual loader; capture identity before the worker thread starts.
     */
    public static <T> Operation<T> generation(Operation<T> operation) {
        Context context = CURRENT.get();
        if (context == null) {
            return operation; // Non-HTTP internal callers have global admission only.
        }
        Client client = context.client();
        consume(client.generations(), "Render rate exceeded");
        return () -> {
            context.owner()
                .acquireGeneration(client);
            try {
                return operation.run();
            } finally {
                context.owner()
                    .releaseGeneration(client);
            }
        };
    }

    private synchronized void acquireGeneration(Client client) {
        if (!client.slots()
            .tryAcquire()) {
            throw new Limited("Client rendering concurrency exceeded", 1);
        }
        retain(client);
    }

    private synchronized void releaseGeneration(Client client) {
        client.slots()
            .release();
        release(client);
    }

    private void retain(Client client) {
        client.leases++;
        clients.put(client.identity, client);
    }

    private synchronized void release(Client client) {
        client.leases--;
        clients.put(client.identity, client);
    }

    private synchronized void releaseRequest(Client client) {
        client.requestsActive--;
        release(client);
    }

    private static void consume(Bucket bucket, String reason) {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long nanos = probe.getNanosToWaitForRefill();
            throw new Limited(reason,
                Math.max(1, nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1)));
        }
    }

    private record Context(ClientRateLimits owner, Client client) {
    }

    private static final class Client {
        final String identity;
        final Bucket requests;
        final Bucket generations;
        final Semaphore slots;
        Bucket logins;
        volatile int leases;
        int requestsActive;

        Client(String identity, Bucket requests, Bucket generations, Semaphore slots) {
            this.identity = identity;
            this.requests = requests;
            this.generations = generations;
            this.slots = slots;
        }

        Bucket requests() {
            return requests;
        }

        Bucket generations() {
            return generations;
        }

        Semaphore slots() {
            return slots;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final ClientRateLimits owner;
        private final Client client;
        private final Context previous;

        private Scope(ClientRateLimits owner, Client client, Context previous) {
            this.owner = owner;
            this.client = client;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            owner.releaseRequest(client);
        }
    }

    public static final class Limited extends ResponseStatusException {
        private static final long serialVersionUID = 1L;
        private final long retryAfter;

        Limited(String reason, long retryAfter) {
            super(HttpStatus.TOO_MANY_REQUESTS, reason);
            this.retryAfter = retryAfter;
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Retry-After", Long.toString(retryAfter));
            headers.setCacheControl("no-store");
            return headers;
        }
    }
}
