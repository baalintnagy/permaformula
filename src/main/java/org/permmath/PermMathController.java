package org.permmath;

import static org.springframework.http.MediaType.IMAGE_PNG;
import static org.springframework.http.MediaType.IMAGE_PNG_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

import java.io.IOException;

import javax.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Validated
@RestController
public class PermMathController {

    @Autowired
    private PermMathService permMathService;

    @GetMapping(path = "/url", produces = TEXT_PLAIN_VALUE)
    public String getUrl(
            @NotBlank @RequestParam String formula,
            @NotBlank @RequestParam String style) {
        return permMathService.getPermMathUrl(formula, style);
    }

    @GetMapping(value = "/image", produces = IMAGE_PNG_VALUE)
    public ResponseEntity<InputStreamResource> getImage(
            @NotBlank @RequestParam String formula,
            @NotBlank @RequestParam String style) throws IOException {

        log.info("formula requested: {} with style {}");

        var imgFile = new ClassPathResource("image.png");

        return ResponseEntity
                .ok()
                .contentType(IMAGE_PNG)
                .body(new InputStreamResource(imgFile.getInputStream()));
    }

}
