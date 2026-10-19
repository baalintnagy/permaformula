package org.permaformula.validation;

import java.util.List;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import org.permaformula.rendering.ApiWorkService;
import org.springframework.beans.factory.annotation.Autowired;

@SupportedValidationTarget(ValidationTarget.PARAMETERS)
public class ComposeValidator implements ConstraintValidator<ValidCompose, Object[]> {
    private final ApiWorkService work;

    public ComposeValidator() {
        work = null;
    }

    @Autowired
    public ComposeValidator(ApiWorkService work) {
        this.work = work;
    }

    @Override
    public boolean isValid(Object[] value, ConstraintValidatorContext context) {
        return ConstraintChecks.check(work, context, () -> {
            @SuppressWarnings("unchecked")
            List<String> refs = (List<String>) value[2];
            DescriptorValidator.bounds((String) value[0], (Integer) value[1], refs,
                (String) value[3]);
        });
    }
}
