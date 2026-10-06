package org.permmath.ratelimit;

import java.time.Duration;
import java.util.List;
import com.google.common.net.InetAddresses;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("permmath.rate-limit")
public record ClientRateLimitProperties(
        @DefaultValue("600") long requestsPerMinute,
        @DefaultValue("120") long requestBurst,
        @DefaultValue("32") int requestConcurrency,
        @DefaultValue("60") long generationsPerMinute,
        @DefaultValue("10") long generationBurst,
        @DefaultValue("1") int generationConcurrency,
        @DefaultValue("100000") int maximumClients,
        @DefaultValue("1h") Duration expireAfterAccess,
        @DefaultValue List<String> trustedProxies) {
    public ClientRateLimitProperties {
        if (requestsPerMinute < 1 || requestBurst < 1 || generationsPerMinute < 1 || generationBurst < 1
                || requestConcurrency < 1 || generationConcurrency < 1 || maximumClients < 1) {
            throw new IllegalArgumentException("Rate limits, bursts, concurrency and client capacity must be positive");
        }
        long expiry = expireAfterAccess.toNanos();
        if (expiry <= 0 || !fullyRefills(expiry, requestsPerMinute, requestBurst)
                || !fullyRefills(expiry, generationsPerMinute, generationBurst)) {
            throw new IllegalArgumentException("Client expiry must cover full refill of both buckets");
        }
        trustedProxies = trustedProxies.stream().map(ip -> InetAddresses.toAddrString(InetAddresses.forString(ip))).toList();
    }

    private static boolean fullyRefills(long nanos, long rate, long burst) {
        return java.math.BigInteger.valueOf(nanos).multiply(java.math.BigInteger.valueOf(rate))
                .compareTo(java.math.BigInteger.valueOf(Duration.ofMinutes(1).toNanos())
                        .multiply(java.math.BigInteger.valueOf(burst))) >= 0;
    }
}
