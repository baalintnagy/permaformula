package org.permmath;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.IMAGE_JPEG;
import static org.springframework.http.MediaType.IMAGE_JPEG_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import static org.springframework.http.ResponseEntity.ok;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import org.permmath.rendering.ImageRendererService;
import org.permmath.url.UrlService;
import org.permmath.validation.ValidImageWidth;
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
    private UrlService urlService;

    @Autowired
    private ImageRendererService imageRendererService;

    @GetMapping(path = "/url", produces = { TEXT_PLAIN_VALUE, APPLICATION_JSON_VALUE })
    public String getUrl(
            @NotBlank @RequestParam String formula,
            @Nullable @ValidImageWidth @RequestParam(required = false) Integer imageWidht,
            @Nullable @RequestParam(required = false) List<@NotBlank String> styleRefs,
            @Nullable @RequestParam(required = false) String style,
            @Nullable @RequestParam(required = false) Boolean compressUrlQuery) throws IOException {
        return urlService.getImageUrl(formula, imageWidht, styleRefs, style, compressUrlQuery);
    }


    @GetMapping(value = "/image", produces = { IMAGE_JPEG_VALUE, IMAGE_JPEG_VALUE, APPLICATION_JSON_VALUE })
    public ResponseEntity<InputStreamResource> getImage(
            @NotBlank @RequestParam String formula,
            @Nullable @ValidImageWidth @RequestParam(required = false) Integer imageWidth,
            @Nullable @RequestParam(required = false) List<@NotBlank String> styleRefs,
            @Nullable @RequestParam(required = false) String style) throws IOException, URISyntaxException {

        return ok()
                .contentType(IMAGE_JPEG)
                .body(imageRendererService.getImage(formula, imageWidth, styleRefs, style));
    }

    @GetMapping(value = "/image-cq", produces = { IMAGE_JPEG_VALUE, IMAGE_JPEG_VALUE, APPLICATION_JSON_VALUE })
    public ResponseEntity<InputStreamResource> getImageCQ(@NotBlank @RequestParam String ubcq) throws IOException, URISyntaxException {
        var queryParams = urlService.decodeUbcq(ubcq);
        return ok()
                .contentType(IMAGE_JPEG)
                .body(imageRendererService.getImage(
                        queryParams.getForumla(),
                        queryParams.getImageWidth(),
                        queryParams.getStyleRefs(),
                        queryParams.getStyle()));

    }

    @GetMapping(path = "/decode", produces = { TEXT_PLAIN_VALUE, APPLICATION_JSON_VALUE })
    public String getDecodedContent(@NotNull @RequestParam URL url) {
        return urlService.urlDecode(url);
    }

    @GetMapping(path = "/decode-cq", produces = { TEXT_PLAIN_VALUE, APPLICATION_JSON_VALUE })
    public String getDecodedContentCQ(@NotNull @RequestParam String param) {
        // TODO
        return null;
    }

}
