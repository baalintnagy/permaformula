package org.permmath;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "permmath")
public record PermMathProperties(
        @NotBlank String host,
        String imageHost,
        @DefaultValue("500") int defaultImageWidth,
        @DefaultValue("2000.0") float defaultDpi) {

    @ConstructorBinding
    public PermMathProperties {
        host = origin(host, "host");
        if (imageHost == null) {
            imageHost = host; // Single-host deployment is the default.
        } else {
            imageHost = origin(imageHost, "image-host");
        }
    }

    public PermMathProperties(String host, int defaultImageWidth, float defaultDpi) {
        this(host, null, defaultImageWidth, defaultDpi);
    }

    private static String origin(String value, String property) {
        URI uri;
        try {
            uri = URI.create(value);
        }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("permmath." + property + " must be an HTTP(S) origin");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() == 0 || uri.getPort() > 65535
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("permmath." + property + " must be an HTTP(S) origin without credentials, path, query or fragment");
        }
        String result = uri.toASCIIString();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    public boolean sameImageOrigin() {
        URI left = URI.create(host);
        URI right = URI.create(imageHost);
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    private static int port(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
