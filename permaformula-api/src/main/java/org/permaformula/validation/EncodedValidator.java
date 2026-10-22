package org.permaformula.validation;

import static org.permaformula.validation.QuickValidator.validate;

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
            validate(StringUtils.isNotBlank(value),
                "Missing encoded payload");
            validate(value.length() <= InputLimits.MAX_ENCODED_CHARS,
                "Encoded payload is too large");
        });
    }
}
