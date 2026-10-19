package org.permaformula.rendering;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import lombok.RequiredArgsConstructor;
import org.permaformula.validation.ValidFormula;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
@RequiredArgsConstructor
public class FormulaSvgRenderer {
    private final RenderGateway gateway;

    public String render(@ValidFormula String formula) throws IOException {
        return new String(gateway.renderFormula(formula), StandardCharsets.UTF_8);
    }
}
