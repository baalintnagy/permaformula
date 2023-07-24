package org.permmath.rendering;

import javax.validation.constraints.NotBlank;

import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Validated
@Component
public class JsBeautifier {

    public String beautify(@NotBlank String jsCode) {
        return jsCode;
    }
}
