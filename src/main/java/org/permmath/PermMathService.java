package org.permmath;

import static java.net.URLEncoder.encode;
import static java.nio.charset.StandardCharsets.UTF_8;

import javax.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Validated
@Service
public class PermMathService {

    @Autowired
    private PermMathProperties permMathProperties;

    public String getPermMathUrl(@NotBlank String formula, @NotBlank String style) {
        return String.format("%s/image?formula=%s&style=%s",
                permMathProperties.getHost(),
                encode(formula, UTF_8),
                encode(style, UTF_8));
    }
}
