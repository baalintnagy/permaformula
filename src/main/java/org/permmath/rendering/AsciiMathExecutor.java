package org.permmath.rendering;

import javax.validation.constraints.NotBlank;

import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
public class AsciiMathExecutor {

    public String applyAsciiMathJs(@NotBlank String input) {
        return input;
    }

}
