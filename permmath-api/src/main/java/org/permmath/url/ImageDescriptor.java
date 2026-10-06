package org.permmath.url;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;

import java.util.List;
import java.util.Objects;

import org.permmath.validation.InputLimits;
import org.permmath.validation.ValidImageWidth;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotBlank;

@JsonInclude(NON_EMPTY)
public record ImageDescriptor(
        String forumla,
        @ValidImageWidth Integer imageWidth,
        List<@NotBlank String> styleRefs,
        String style) {

    public ImageDescriptor {
        Objects.requireNonNull(forumla, "forumla");
        InputLimits.descriptor(forumla, imageWidth, styleRefs, style);
        styleRefs = styleRefs == null ? null : List.copyOf(styleRefs);
    }
}
