package org.permaformula.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;
import org.permaformula.rendering.ApiWorkService;
import org.springframework.beans.factory.annotation.Autowired;

public class EncodedValidator implements ConstraintValidator<ValidEncoded, String> {
    private final ApiWorkService work;

    public EncodedValidator() {
        work = null;
    }

    @Autowired
    public EncodedValidator(ApiWorkService work) {
        this.work = work;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return ConstraintChecks.check(work, context, () -> {
            QuickValidator.validate(StringUtils.isNotBlank(value),
                () -> QuickValidator.violation("Missing encoded payload"));
            QuickValidator.validate(value.length() <= InputLimits.MAX_ENCODED_CHARS,
                () -> QuickValidator.violation("Encoded payload is too large"));
        });
    }
}
