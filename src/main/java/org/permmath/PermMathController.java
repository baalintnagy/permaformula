package org.permmath;

import static org.springframework.http.MediaType.IMAGE_JPEG;
import static org.springframework.http.MediaType.IMAGE_JPEG_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import static org.springframework.http.ResponseEntity.ok;

import java.util.List;

import javax.validation.constraints.NotBlank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@Validated
@RestController
public class PermMathController {

    @Autowired
    private PermMathUrlService permMathUrlService;

    @Autowired
    private PermMathRendererService permMathRendererService;

    @GetMapping(path = "/url", produces = TEXT_PLAIN_VALUE)
    public String getUrl(
            @NotBlank @RequestParam String formula,
            @Nullable @RequestParam(required = false) List<@NotBlank String> styleRefs,
            @Nullable @RequestParam(required = false) String style) {
        return permMathUrlService.getPermMathUrl(formula, styleRefs, style);
    }

    @GetMapping(value = "/image", produces = { IMAGE_JPEG_VALUE, IMAGE_JPEG_VALUE })
    public ResponseEntity<InputStreamResource> getImage(
            @NotBlank @RequestParam String formula,
            @Nullable @RequestParam(required = false) List<@NotBlank String> styleRefs,
            @Nullable @RequestParam(required = false) String style) throws Exception {

        /*
         * return ResponseEntity .ok() .contentType(IMAGE_PNG) .body(new
         * InputStreamResource(new ClassPathResource("image.png").getInputStream()));
         */

        return ok()
                .contentType(IMAGE_JPEG)
                .body(
                        permMathRendererService.getImage(formula, styleRefs, style));
    }

}
