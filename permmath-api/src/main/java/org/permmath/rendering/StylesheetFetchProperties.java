package org.permmath.rendering;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** A trusted deployment proxy, never a request-supplied URL. Direct access is a local-development option. */
@ConfigurationProperties("permmath.stylesheet-fetch")
public record StylesheetFetchProperties(@DefaultValue("false") boolean proxyRequired, URI proxy) {
    public StylesheetFetchProperties {
        if (proxyRequired && proxy == null) throw new IllegalArgumentException("Stylesheet egress proxy is required");
        if (proxy != null && (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                || proxy.getPort() < 1 || proxy.getPort() > 65535 || proxy.getRawUserInfo() != null
                || proxy.getRawQuery() != null || proxy.getRawFragment() != null
                || proxy.getRawPath() != null && !proxy.getRawPath().isEmpty())) {
            throw new IllegalArgumentException("Stylesheet proxy must be an HTTP host and explicit port without credentials or path");
        }
    }

    static StylesheetFetchProperties direct() { return new StylesheetFetchProperties(false, null); }
}
