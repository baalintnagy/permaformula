package org.permmath.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class FormulaSvgRenderer {
    private final RenderGateway gateway;
    public FormulaSvgRenderer(RenderGateway gateway) { this.gateway = gateway; }
    public String render(String formula) throws IOException {
        return new String(gateway.renderFormula(formula), StandardCharsets.UTF_8);
    }
}
