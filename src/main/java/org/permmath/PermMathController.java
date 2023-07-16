package org.permmath;

import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import javax.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class PermMathController {

    @Autowired
    private PermMathService permMathService;

    @GetMapping(path = "/url", produces = TEXT_PLAIN_VALUE)
    public String getPermMathUrl(@NotBlank String asciiMath, @NotBlank String css) {
        return permMathService.getPermMathUrl(asciiMath, css);
    }

}
