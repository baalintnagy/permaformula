package org.permmath;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;


import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import static org.springframework.http.ResponseEntity.ok;

import java.io.IOException;
import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.permmath.rendering.ImageRendererService;
import org.permmath.rendering.FormulaSvgRenderer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.permmath.url.ImageDescriptor;
import org.permmath.url.UrlService;
import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.jspecify.annotations.Nullable;
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

    @Autowired
    private FormulaSvgRenderer formulaSvgRenderer;

    @Operation(summary = "Render a single ASCIIMath formula as SVG",
            description = "Pass a compressed image descriptor in ubcq. Its forumla field must contain plain ASCIIMath without HTML or backtick delimiters. "
                    + "Returns a standalone SVG with embedded glyph paths. No browser is launched.")
    @GetMapping(path = "/img", produces = "image/svg+xml")
    public ResponseEntity<String> getFormulaSvg(
            @Parameter(description = "GZIP/Base64 image descriptor, URL-encoded as a query parameter")
            @NotBlank @RequestParam String ubcq) throws IOException {
        var descriptor = urlService.decodeUbcq(ubcq);
        if (descriptor.forumla().isBlank()) throw new IllegalArgumentException("Formula must not be blank");
        return ok().contentType(org.springframework.http.MediaType.parseMediaType("image/svg+xml"))
                .body(formulaSvgRenderer.render(descriptor.forumla()));
    }

    @GetMapping(path = "/url", produces = { TEXT_PLAIN_VALUE, APPLICATION_JSON_VALUE })
    public String getUrl(
            @NotBlank @RequestParam String formula,
            @Nullable @ValidImageWidth @RequestParam(required = false) Integer imageWidth,
            @Nullable @RequestParam(required = false) List<@NotBlank String> styleRefs,
            @Nullable @RequestParam(required = false) String style) throws IOException {
        return urlService.getImageUrl(formula, imageWidth, styleRefs, style);
    }


    @GetMapping(value = "/image", produces = "image/svg+xml")
    public ResponseEntity<InputStreamResource> getImage(@NotBlank @RequestParam String ubcq) throws IOException {
        return ok()
                .contentType(org.springframework.http.MediaType.parseMediaType("image/svg+xml"))
                .body(imageRendererService.getImage(
                        urlService.decodeUbcq(ubcq)));

    }

    @GetMapping(path = "/decode", produces = { APPLICATION_JSON_VALUE })
    public ImageDescriptor getDecodedContent(@NotBlank @RequestParam String ubcq) throws IOException {
        return urlService.decodeUbcq(ubcq);
    }

}
