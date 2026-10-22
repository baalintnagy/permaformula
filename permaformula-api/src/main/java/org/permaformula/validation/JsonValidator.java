package org.permaformula.validation;

import static org.permaformula.validation.QuickValidator.notNull;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.permaformula.rendering.ApiWorkService;
import org.springframework.beans.factory.annotation.Autowired;

public class JsonValidator implements ConstraintValidator<ValidJson, String> {
    private final ApiWorkService work;

    public JsonValidator() {
        work = null;
    }

    @Autowired
    public JsonValidator(ApiWorkService work) {
        this.work = work;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return ConstraintChecks.check(work, context, () -> {
            notNull(value, "Missing uncompressed payload");
            ValidationText.text(value, InputLimits.MAX_JSON_BYTES, "Uncompressed payload");
        });
    }
}
