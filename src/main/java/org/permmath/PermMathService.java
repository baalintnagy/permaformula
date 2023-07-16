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

    public String getPermMathUrl(@NotBlank String asciiMath, @NotBlank String css) {
        return String.format("%s?ascii-math=%s&css=%s",
                permMathProperties.getHost(),
                encode(asciiMath, UTF_8),
                encode(css, UTF_8));
    }
}
