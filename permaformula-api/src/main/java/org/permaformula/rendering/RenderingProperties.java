package org.permaformula.rendering;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("permaformula.rendering")
public record RenderingProperties(
    @DefaultValue("localhost:9090") String target,
    @DefaultValue("45s") Duration timeout,
    Path socket,
    @DefaultValue("false") boolean plaintext,
    Path tlsCa,
    Path tlsCertificate,
    Path tlsKey) {
}
