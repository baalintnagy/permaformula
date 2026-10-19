package org.permaformula.rendering;

import org.permaformula.url.ImageDescriptor;

/** API presentation rules used by the descriptor constraint before cache or renderer work. */
public final class PresentationValidation {
    public static void validate(ImageDescriptor descriptor) {
        if (descriptor.styleRefs() != null) {
            descriptor.styleRefs().forEach(StylesheetFetcher::destination);
        }
        RenderContentPolicy.validateHtml(descriptor.forumla());
        StylesheetPolicy.stylesheet(descriptor.style());
    }

    private PresentationValidation() {
    }
}
