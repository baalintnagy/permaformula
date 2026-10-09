package org.permmath;

import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import static org.springframework.http.ResponseEntity.ok;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.permmath.delivery.ImageDelivery;
import org.permmath.rendering.GeneratedImageService;
import org.permmath.rendering.SvgOutputPolicy;
import org.permmath.url.UrlService;
import org.permmath.validation.InputLimits;
import org.permmath.validation.ValidImageWidth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotBlank;

@Validated
@RestController
public class PermMathController {

    @Autowired
    private UrlService urlService;

    @Autowired
    private GeneratedImageService generatedImageService;

    @Operation(summary = "Render a single AsciiMath formula as SVG",
            description = "Pass an uncompressed plain [AsciiMath](https://asciimath.org/) formula without HTML or backtick delimiters. Dimensions follow the formula; styling and layout options are not supported. "
                    + "Returns a standalone SVG with embedded glyph paths. No browser is launched.")
    @GetMapping(path = "/exp", produces = "image/svg+xml")
    @ImageDelivery
    public ResponseEntity<String> getExp(
        @Parameter(description = "Plain [AsciiMath](https://asciimath.org/) formula, URL-encoded as a query parameter", example = "sqrt(x^2 + y^2)") @NotBlank @RequestParam String formula)
        throws IOException {

        InputLimits.formula(formula);
        return svgResponse(generatedImageService.getExp(formula));
    }

    @Operation(summary = "Create a compressed image descriptor",
            description = """
                Serializes the content and optional layout settings into a UTF-8 JSON descriptor, \
                compresses it with GZIP and Base64, and returns only the raw Base64 ubcq value. URL-encode this value when building /html?ubcq=... or /html-jpg?ubcq=... URLs. \
                No rendering is performed. For formula-only SVG, call /exp directly with its formula parameter.""")
    @GetMapping(path = "/ubcq", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getUbcq(
        @Parameter(description = "HTML content with backtick-delimited [AsciiMath](https://asciimath.org/) for /html",
            example = """
                <div class="math-card">
                  <span class="eyebrow">MATHEMATICAL IDENTITY</span>
                  <h1>The sum of cubes</h1>
                  <p>The sum has a closed form:</p>
                  <div class="equation">
                    `sum_(i=1)^n i^3 = ((n(n+1))/2)^2`
                  </div>
                  <p class="caption">The sum of the first n cubes equals
                    the square of the n-th triangular number.</p>
                </div>""")
        @NotBlank @RequestParam String formula,
        @Parameter(description = "HTML layout width in CSS pixels, from 220 to 4000. Defaults to the configured width (500 by default).", example = "500") @Nullable @ValidImageWidth @RequestParam(required = false) Integer imageWidth,
        @Parameter(description = "External HTTPS stylesheet URLs in application order. Fetched with SSRF checks and sanitized before rendering. Repeat for multiple stylesheets.")
        @Nullable @RequestParam(required = false) List<@NotBlank String> styleRefs,
        @Parameter(description = "Presentation CSS stylesheet. Unsupported rules and properties are removed by /html.",
                    example = """
                body { margin: 0; padding: 0; }
                .math-card {
                  padding: 48px;
                  background: #edf3fb;
                  color: #263b54;
                  font-family: Georgia, serif;
                }
                .eyebrow {
                  font: 11px Arial, sans-serif;
                  letter-spacing: 2px;
                  color: #496f9f;
                }
                h1 { font-size: 36px; margin: 18px 0; }
                p { line-height: 1.7; }
                .equation {
                  padding: 24px 0;
                  font-size: 25px;
                }
                .caption { color: #687d96; font-size: 14px; }""")
        @Nullable @RequestParam(required = false) String style) throws IOException {
        return ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(urlService.getUbcq(formula, imageWidth, styleRefs, style));
    }


    @Operation(summary = "Render compressed styled HTML as SVG",
            description = """
                Decodes the payload generated by /ubcq and renders its HTML, CSS and backtick-delimited [AsciiMath](https://asciimath.org/) \
                using Chromium. Returns SVG with styled HTML inside foreignObject \
                and math glyphs as vector paths. Intended for browsers; use /exp for standalone formula SVG.""")
    @GetMapping(value = "/html", produces = "image/svg+xml")
    @ImageDelivery
    public ResponseEntity<String> getHtml(
        @Parameter(description = "Raw Base64 descriptor returned by /ubcq. Paste it into Swagger; it handles URL encoding.",
                    example = "H4sIAAAAAAACCqtWSssvKs3NSVSyUkqoiDNKUNJRysxNTE8Nz0wpyVCyMjIyqAUAxqPtbyQAAAA=")
        @NotBlank @RequestParam String ubcq) throws IOException {
        return svgResponse(generatedImageService.getHtml(urlService.decodeUbcq(ubcq)));

    }

    @Operation(summary = "Render a single AsciiMath formula as high quality JPEG",
            description = "Accepts the same formula as /exp. Generates and caches the source SVG, then caches its JPEG conversion. White background, up to 2x resolution and 95% quality.")
    @GetMapping(path = "/exp-jpg", produces = "image/jpeg")
    @ImageDelivery
    public ResponseEntity<byte[]> getExpJpg(@NotBlank @RequestParam String formula) throws IOException {
        InputLimits.formula(formula);
        return jpegResponse(generatedImageService.getExpJpg(formula));
    }

    @Operation(summary = "Render compressed styled HTML as high quality JPEG",
            description = "Accepts the same ubcq as /html. Generates and caches the source SVG, then caches its JPEG conversion including styled foreignObject content. White background, up to 2x resolution and 95% quality.")
    @GetMapping(path = "/html-jpg", produces = "image/jpeg")
    @ImageDelivery
    public ResponseEntity<byte[]> getHtmlJpg(@NotBlank @RequestParam String ubcq) throws IOException {
        return jpegResponse(generatedImageService.getHtmlJpg(urlService.decodeUbcq(ubcq)));
    }

    private static ResponseEntity<byte[]> jpegResponse(byte[] jpeg) {
        return ok().contentType(MediaType.IMAGE_JPEG)
                .header("Content-Security-Policy", SvgOutputPolicy.CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(jpeg);
    }

    private static ResponseEntity<String> svgResponse(String svg) {
        return ok().contentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"))
                .header("Content-Security-Policy", SvgOutputPolicy.CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(svg);
    }

}
