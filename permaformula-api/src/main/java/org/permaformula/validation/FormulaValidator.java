package org.permaformula.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.apache.commons.lang3.StringUtils;
import org.permaformula.contract.ComplexityPolicy;
import org.permaformula.rendering.ApiWorkService;
import org.springframework.beans.factory.annotation.Autowired;

public class FormulaValidator implements ConstraintValidator<ValidFormula, String> {
    private final ApiWorkService work;

    public FormulaValidator() {
        work = null;
    }

    @Autowired
    public FormulaValidator(ApiWorkService work) {
        this.work = work;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return ConstraintChecks.check(work, context, () -> {
            ValidationText.text(value, InputLimits.MAX_FORMULA_BYTES, "Formula");
            QuickValidator.validate(StringUtils.isNotBlank(value),
                () -> QuickValidator.violation("Formula must not be blank"));
            ComplexityPolicy.CURRENT.checkMathInput(value);
        });
    }
}
