package org.permaformula.ratelimit;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;

import com.google.common.net.InetAddresses;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.permaformula.validation.QuickValidator;
import org.permaformula.validation.RecordValidation;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("permaformula.rate-limit")
public record ClientRateLimitProperties(
    @DefaultValue("600") @Positive long requestsPerMinute,
    @DefaultValue("120") @Positive long requestBurst,
    @DefaultValue("32") @Positive int requestConcurrency,
    @DefaultValue("60") @Positive long generationsPerMinute,
    @DefaultValue("10") @Positive long generationBurst,
    @DefaultValue("1") @Positive int generationConcurrency,
    @DefaultValue("100000") @Positive int maximumClients,
    @DefaultValue("1h") @NotNull Duration expireAfterAccess,
    @DefaultValue @NotNull List<String> trustedProxies) {
    public ClientRateLimitProperties {
        RecordValidation.requireValid(ClientRateLimitProperties.class, requestsPerMinute,
            requestBurst, requestConcurrency, generationsPerMinute, generationBurst,
            generationConcurrency, maximumClients, expireAfterAccess, trustedProxies);
        long expiry = expireAfterAccess.toNanos();
        QuickValidator.validate(
            !(expiry <= 0
                || !fullyRefills(expiry, requestsPerMinute, requestBurst)
                || !fullyRefills(expiry, generationsPerMinute, generationBurst)),
            () -> new IllegalArgumentException(
                "Client expiry must cover full refill of both buckets"));
        trustedProxies = trustedProxies.stream()
            .map(ip -> InetAddresses.toAddrString(InetAddresses.forString(ip)))
            .toList();
    }

    private static boolean fullyRefills(long nanos, long rate, long burst) {
        return BigInteger.valueOf(nanos).multiply(BigInteger.valueOf(rate))
            .compareTo(BigInteger.valueOf(Duration.ofMinutes(1).toNanos())
                .multiply(BigInteger.valueOf(burst))) >= 0;
    }
}
