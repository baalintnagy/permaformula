package org.permaformula.url;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static org.permaformula.validation.QuickValidator.notNull;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.permaformula.ApiExamples;
import org.permaformula.validation.InputLimits;
import org.permaformula.validation.ValidImageWidth;

@Schema(
    description =
        """
        Decoded original image sources: forumla (historical spelling), optional CSS, ordered \
        stylesheet references and canvas width. Formula-only library entries use forumla as plain \
        AsciiMath and omit layout and styling.\
        """
)
@JsonInclude(NON_EMPTY)
public record ImageDescriptor(
    @Schema(
        description =
            """
            Original HTML with backtick-delimited AsciiMath. The historical forumla spelling is \
            part of the payload contract.\
            """
        ,
        example = "`x^2`") @NotBlank String forumla,
    @Schema(
        description =
            """
            Optional encoded canvas width in CSS pixels. Omitted when not supplied; renderers then \
            use the configured default (500 by default).\
            """
        ,
        example = "220", minimum = "220", maximum = "4000") @ValidImageWidth Integer imageWidth,
    @Schema(description = "Optional external HTTPS stylesheet URLs, preserving application order.",
        example = ApiExamples.STYLESHEET_LIST) @Size(
            max = InputLimits.MAX_STYLE_REFS) List<@NotBlank String> styleRefs,
    @Schema(description = "Optional original presentation CSS, before rendering sanitization.",
        example = "p { color: #263b54; }") String style) {

    public ImageDescriptor {
        notNull(forumla, "forumla");
        styleRefs = styleRefs == null ? null
            : List.copyOf(styleRefs);
    }
}
