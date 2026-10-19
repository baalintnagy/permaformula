package org.permaformula;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;
import org.permaformula.validation.QuickValidator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "permaformula")
public record PermaFormulaProperties(
    @NotBlank String host,
    String imageHost,
    @DefaultValue("500") int defaultImageWidth,
    @DefaultValue("2000.0") float defaultDpi) {

    @ConstructorBinding
    public PermaFormulaProperties {
        host = origin(host, "host");
        if (imageHost == null) {
            imageHost = host; // Single-host deployment is the default.
        } else {
            imageHost = origin(imageHost, "image-host");
        }
    }

    public PermaFormulaProperties(String host, int defaultImageWidth, float defaultDpi) {
        this(host, null, defaultImageWidth, defaultDpi);
    }

    private static String origin(String value, String property) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException | NullPointerException _) {
            throw new IllegalArgumentException(
                "permaformula." + property + " must be an HTTP(S) origin");
        }
        QuickValidator.validate(
            !(!("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getPort() == 0
                || uri.getPort() > 65535
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty()
                    || "/".equals(uri.getRawPath()))),
            () -> new IllegalArgumentException(
                """
                    permaformula.%s must be an HTTP(S) origin without credentials, path, query or fragment\
                    """
                    .formatted(property)));
        String result = uri.toASCIIString();
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    public boolean sameImageOrigin() {
        URI left = URI.create(host);
        URI right = URI.create(imageHost);
        return left.getScheme().equalsIgnoreCase(right.getScheme())
            && left.getHost().equalsIgnoreCase(right.getHost())
            && port(left) == port(right);
    }

    private static int port(URI uri) {
        return uri.getPort() != -1 ? uri.getPort()
            : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
