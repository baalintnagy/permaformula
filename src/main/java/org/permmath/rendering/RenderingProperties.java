package org.permmath.rendering;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("permmath.rendering")
public record RenderingProperties(
        @DefaultValue("chromium") Engine engine,
        @DefaultValue(".runtime/browsers") Path browserDirectory,
        @DefaultValue("30s") Duration timeout) {
    public enum Engine { JAVAFX, CHROMIUM }
}
