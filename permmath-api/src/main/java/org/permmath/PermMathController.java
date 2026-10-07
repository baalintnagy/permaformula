package org.permmath;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
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
    @GetMapping(path = "/img", produces = "image/svg+xml")
    @ImageDelivery
    public ResponseEntity<String> getImg(
        @Parameter(description = "Plain [AsciiMath](https://asciimath.org/) formula, URL-encoded as a query parameter", example = "sqrt(x^2 + y^2)") @NotBlank @RequestParam String formula)
        throws IOException {

        InputLimits.formula(formula);
        return svgResponse(generatedImageService.getImg(formula));
    }

    @Operation(summary = "Create a compressed image permalink",
            description = """
                Serializes the content and optional layout settings into a UTF-8 JSON descriptor, \
                compresses it with GZIP and Base64, and returns an /image?ubcq=... permalink. \
                No rendering is performed. For formula-only SVG, call /img directly with its formula parameter.""")
    @GetMapping(path = "/url", produces = { TEXT_PLAIN_VALUE, APPLICATION_JSON_VALUE })
    public String getUrl(
        @Parameter(description = "HTML content with backtick-delimited [AsciiMath](https://asciimath.org/) for /image",
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
        @Parameter(description = "Presentation CSS stylesheet. Unsupported rules and properties are removed by /image.",
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
        return urlService.getImageUrl(formula, imageWidth, styleRefs, style);
    }


    @Operation(summary = "Render compressed styled HTML as SVG",
            description = """
                Decodes the payload generated by /url and renders its HTML, CSS and backtick-delimited [AsciiMath](https://asciimath.org/) \
                using Chromium. Returns SVG with styled HTML inside foreignObject \
                and math glyphs as vector paths. Intended for browsers; use /img for standalone formula SVG.""")
    @GetMapping(value = "/image", produces = "image/svg+xml")
    @ImageDelivery
    public ResponseEntity<String> getImage(
        @Parameter(description = "Compressed descriptor from the ubcq parameter of a /url permalink. Paste the decoded parameter value into Swagger; it handles URL encoding.",
                    example = "H4sIAAAAAAACCqtWSssvKs3NSVSyUkqoiDNKUNJRysxNTE8Nz0wpyVCyMjIyqAUAxqPtbyQAAAA=")
        @NotBlank @RequestParam String ubcq) throws IOException {
        return svgResponse(generatedImageService.getImage(urlService.decodeUbcq(ubcq)));

    }

    @Operation(summary = "URL-encode text as a UTF-8 query value",
            description = "Uses Java form/query-component encoding: spaces become + and reserved characters are percent-encoded. "
                    + "Accepts empty text and up to 4 MiB of UTF-8 input. Returns plain text; supply the original text in Swagger.")
    @GetMapping(path = "/url-encode", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getUrlEncode(
            @Parameter(description = "Text to encode as one query parameter value", example = "x + y = 50%")
            @RequestParam String text) throws IOException {
        return ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(urlService.encodeText(text));
    }

    private static ResponseEntity<String> svgResponse(String svg) {
        return ok().contentType(MediaType.parseMediaType("image/svg+xml;charset=UTF-8"))
                .header("Content-Security-Policy", SvgOutputPolicy.CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(svg);
    }

}
