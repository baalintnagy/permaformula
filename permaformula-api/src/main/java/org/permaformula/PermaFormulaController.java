package org.permaformula;

import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;
import static org.springframework.http.ResponseEntity.ok;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.permaformula.delivery.ImageDelivery;
import org.permaformula.rendering.GeneratedImageService;
import org.permaformula.rendering.SvgOutputPolicy;
import org.permaformula.url.ImageDescriptor;
import org.permaformula.url.UrlService;
import org.permaformula.validation.InputLimits;
import org.permaformula.validation.ValidCompose;
import org.permaformula.validation.ValidEncoded;
import org.permaformula.validation.ValidFormula;
import org.permaformula.validation.ValidImageWidth;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ApiResponses({
        @ApiResponse(responseCode = "400",
            description = "Missing, blank, malformed or oversized input; invalid width or descriptor.",
            content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "429",
            description = "Client request or generation quota exhausted. Retry-After indicates when to retry.",
            content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "503",
            description = "Preparation or rendering capacity unavailable. Retry after the service recovers.",
            content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(responseCode = "504",
            description = "Request deadline exceeded during preparation, cache waiting or rendering.",
            content = @Content(mediaType = "application/problem+json",
                schema = @Schema(implementation = ProblemDetail.class)))
})
@RequiredArgsConstructor
public class PermaFormulaController {

    private final UrlService urlService;

    private final GeneratedImageService generatedImageService;

    @Operation(summary = "Render a single AsciiMath formula as SVG",
        description = """
            Supply a plain AsciiMath formula without HTML or backtick delimiters. \
            Dimensions follow the formula; styling and layout \
            options are not supported. Returns a standalone SVG with embedded glyph paths. No \
            browser is launched.\
            """)
    @GetMapping(path = "/exp-svg", produces = "image/svg+xml")
    @ApiResponse(responseCode = "200",
        description = "Standalone formula SVG with embedded vector glyphs; generated images are cached.",
        content = @Content(mediaType = "image/svg+xml", schema = @Schema(type = "string")))
    @ImageDelivery
    public ResponseEntity<String> getExp(
        @ValidFormula @Parameter(description = "Plain AsciiMath formula.",
            example = ApiExamples.FORMULA) @NotBlank @RequestParam String formula)
        throws IOException {
        return svgResponse(generatedImageService.getExp(formula));
    }

    @Operation(summary = "Create a compressed image descriptor",
        description = """
            Serializes the content and optional layout settings into a UTF-8 JSON descriptor, \
            compresses it with GZIP and Base64, and returns only the raw Base64 ubcq value. URL-encode this value when building /html-svg?ubcq=... or /html-jpg?ubcq=... URLs. \
            No rendering is performed. For formula-only images use the formula parameter on /exp-svg or /exp-jpg.""")
    @GetMapping(path = "/ubcq", produces = TEXT_PLAIN_VALUE)
    @ApiResponse(responseCode = "200",
        description = """
            Raw GZIP/Base64 descriptor, without an origin, path or query prefix. No rendering; \
            Cache-Control: no-store.\
            """,
        content = @Content(mediaType = TEXT_PLAIN_VALUE, schema = @Schema(type = "string"),
            examples = @ExampleObject(name = "simpleFormula", value = ApiExamples.UBCQ,
                summary = "Descriptor for `x^2` at 220px")))
    @ValidCompose
    public ResponseEntity<String> getUbcq(
        @Parameter(
            description = "HTML content with backtick-delimited [AsciiMath](https://asciimath.org/) for /html-svg",
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
                </div>""") @NotBlank @RequestParam String formula,
        @Parameter(
            description = """
                HTML layout width in CSS pixels, from 220 to 4000. Defaults to the configured \
                width (500 by default).\
                """,
            example = "500") @Nullable @ValidImageWidth @RequestParam(
                required = false) Integer imageWidth,
        @Parameter(
            description = "Optional external HTTPS stylesheet URLs in application order. Repeat styleRefs for multiple stylesheets. "
                + "Example: " + ApiExamples.STYLESHEET
                + """
                    . Stored by /ubcq; fetched with SSRF checks and validated by the HTML \
                    rendering endpoints. Leave the example list empty to use only inline CSS.\
                    """,
            example = "[]",
            array = @ArraySchema(schema = @Schema(type = "string", format = "uri",
                example = ApiExamples.STYLESHEET))) @Nullable @RequestParam(
                    required = false) @Size(
                        max = InputLimits.MAX_STYLE_REFS) List<@NotBlank String> styleRefs,
        @Parameter(
            description = """
                Presentation CSS stylesheet. Stored unchanged in the descriptor; unsupported rules \
                and properties are rejected with HTTP 400 by /html-svg and /html-jpg.\
                """,
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
                .caption { color: #687d96; font-size: 14px; }""") @Nullable @RequestParam(
                required = false) String style)
        throws IOException {
        return ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
            .header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .body(urlService.getUbcq(formula, imageWidth, styleRefs, style));
    }

    @Operation(summary = "Decode a compressed image descriptor",
        description = """
            Returns the original descriptor as application/json without rendering, sanitizing the \
            sources or fetching stylesheets. The historical field name forumla contains HTML with \
            backtick-delimited AsciiMath; style contains CSS and styleRefs preserves stylesheet \
            order. Absent or empty optional fields are omitted, and imageWidth is returned only if \
            encoded in the payload. Useful for loading existing image URLs into the Builder's \
            Decode UBCQ section. Responses use Cache-Control: no-store.\
            """)
    @GetMapping(path = "/ubcq-decode", produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
        description = "Original descriptor fields, with no rendering or source sanitization.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = ImageDescriptor.class),
            examples = @ExampleObject(name = "simpleFormula", value = ApiExamples.DESCRIPTOR,
                summary = "Decoded `x^2` descriptor at 220px")))
    public ResponseEntity<ImageDescriptor> decodeUbcq(
        @ValidEncoded @Parameter(description = ApiExamples.UBCQ_DESCRIPTION,
            example = ApiExamples.UBCQ) @NotBlank @Size(
                max = InputLimits.MAX_ENCODED_CHARS) @RequestParam String ubcq)
        throws IOException {
        return ok().header("Cache-Control", "no-store")
            .header("X-Content-Type-Options", "nosniff")
            .header("Referrer-Policy", "no-referrer")
            .body(urlService.decodeUbcq(ubcq));
    }

    @Operation(summary = "Render compressed styled HTML as SVG",
        description = """
            Decodes the payload generated by /ubcq and renders its HTML, CSS and backtick-delimited [AsciiMath](https://asciimath.org/) \
            using Chromium. Returns SVG with styled HTML inside foreignObject \
            and math glyphs as vector paths. Intended for browsers; use /exp-svg for standalone formula SVG.""")
    @GetMapping(value = "/html-svg", produces = "image/svg+xml")
    @ApiResponse(responseCode = "200",
        description = """
            Cached SVG containing validated styled HTML in foreignObject and vector math glyphs. \
            Canvas width follows the descriptor or configured default.\
            """,
        content = @Content(mediaType = "image/svg+xml", schema = @Schema(type = "string")))
    @ImageDelivery
    public ResponseEntity<String> getHtml(
        @ValidEncoded @Parameter(description = ApiExamples.UBCQ_DESCRIPTION,
            example = ApiExamples.UBCQ) @NotBlank @Size(
                max = InputLimits.MAX_ENCODED_CHARS) @RequestParam String ubcq)
        throws IOException {
        return svgResponse(generatedImageService.getHtmlUbcq(ubcq));

    }

    @Operation(summary = "Render a single AsciiMath formula as high quality JPEG",
        description = """
            Accepts the same formula or UBCQ parameter as /exp-svg, without HTML, backticks or \
            styling options. Generates and caches the source SVG, then rasterizes it with isolated \
            Chromium and caches the JPEG conversion. Returns binary image/jpeg on a white \
            background, at up to 2x CSS resolution and 95% quality within the rendering limits. A \
            source SVG miss and a JPEG conversion consume separate generation allowances.\
            """)
    @GetMapping(path = "/exp-jpg", produces = "image/jpeg")
    @ApiResponse(responseCode = "200",
        description = "Binary JPEG of the formula; transparent areas become white.",
        content = @Content(mediaType = "image/jpeg",
            schema = @Schema(type = "string", format = "binary")))
    @ImageDelivery
    public ResponseEntity<byte[]> getExpJpg(
        @ValidFormula @Parameter(description = "Plain AsciiMath formula.",
            example = ApiExamples.FORMULA) @NotBlank @RequestParam String formula)
        throws IOException {
        return jpegResponse(generatedImageService.getExpJpg(formula));
    }

    @Operation(summary = "Render compressed styled HTML as high quality JPEG",
        description = """
            Accepts the same GZIP/Base64 descriptor as /html-svg. Validates input, generates and caches \
            its source SVG, then rasterizes styled foreignObject content and vector math with \
            isolated Chromium and caches the JPEG conversion. Returns binary image/jpeg on a white \
            background, at up to 2x CSS resolution and 95% quality within rendering limits. Canvas \
            width and CSS follow the descriptor. A source SVG miss and a JPEG conversion consume \
            separate generation allowances.\
            """)
    @GetMapping(path = "/html-jpg", produces = "image/jpeg")
    @ApiResponse(responseCode = "200",
        description = "Binary JPEG including validated HTML, CSS and formulas; transparent areas become white.",
        content = @Content(mediaType = "image/jpeg",
            schema = @Schema(type = "string", format = "binary")))
    @ImageDelivery
    public ResponseEntity<byte[]> getHtmlJpg(
        @ValidEncoded @Parameter(description = ApiExamples.UBCQ_DESCRIPTION,
            example = ApiExamples.UBCQ) @NotBlank @Size(
                max = InputLimits.MAX_ENCODED_CHARS) @RequestParam String ubcq)
        throws IOException {
        return jpegResponse(generatedImageService.getHtmlJpgUbcq(ubcq));
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
