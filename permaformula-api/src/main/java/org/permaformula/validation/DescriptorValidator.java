package org.permaformula.validation;

import java.util.List;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;
import org.permaformula.rendering.ApiWorkService;
import org.permaformula.rendering.PresentationValidation;
import org.permaformula.url.ImageDescriptor;
import org.springframework.beans.factory.annotation.Autowired;

public class DescriptorValidator implements ConstraintValidator<ValidDescriptor, ImageDescriptor> {
    private final ApiWorkService work;

    public DescriptorValidator() {
        work = null;
    }

    @Autowired
    public DescriptorValidator(ApiWorkService work) {
        this.work = work;
    }

    private boolean presentation;

    @Override
    public void initialize(ValidDescriptor constraint) {
        presentation = constraint.presentation();
    }

    @Override
    public boolean isValid(ImageDescriptor value, ConstraintValidatorContext context) {
        return ConstraintChecks.check(work, context, () -> {
            QuickValidator.notNull(value,
                () -> QuickValidator.violation("Missing image descriptor"));
            bounds(value.forumla(), value.imageWidth(), value.styleRefs(), value.style());
            if (presentation) {
                PresentationValidation.validate(value);
            }
        });
    }

    static void bounds(String content, Integer width, List<String> refs, String style) {
        ValidationText.text(content, InputLimits.MAX_CONTENT_BYTES, "Content");
        QuickValidator.validate(
            StringUtils.isNotBlank(content),
            () -> QuickValidator.violation("Content must not be blank"));
        QuickValidator.validate(
            width == null
                || width >= 220
                    && width <= 4000,
            () -> QuickValidator.violation("Image width must be between 220 and 4000"));
        ValidationText.text(style, InputLimits.MAX_STYLE_BYTES, "Style");
        if (refs != null) {
            QuickValidator.validate(
                refs.size() <= InputLimits.MAX_STYLE_REFS,
                () -> QuickValidator.violation("At most eight stylesheet references are allowed"));
            refs.forEach(ref -> {
                ValidationText.text(ref, InputLimits.MAX_REF_BYTES, "Stylesheet reference");
                QuickValidator.validate(
                    StringUtils.isNotBlank(ref),
                    () -> QuickValidator.violation("Stylesheet references must not be blank"));
            });
        }
    }
}
