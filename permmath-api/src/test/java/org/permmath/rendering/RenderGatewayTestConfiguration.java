package org.permmath.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class RenderGatewayTestConfiguration {
    @Bean @Primary
    RenderGateway testRenderGateway() {
        return new RenderGateway() {
            public byte[] renderFormula(String formula) throws IOException {
                try (var renderer = new AsciiMathRenderer()) {
                    return renderer.render(formula).getBytes(StandardCharsets.UTF_8);
                }
            }
            public byte[] renderHtml(String html, int width) throws IOException {
                try (var assets = new RenderAssets();
                     var renderer = new ChromiumRenderer(new BrowserSettings(
                             Path.of("../.runtime/browsers"), Duration.ofSeconds(30), false))) {
                    return renderer.render(assets.add(html), width);
                }
            }
        };
    }
}
