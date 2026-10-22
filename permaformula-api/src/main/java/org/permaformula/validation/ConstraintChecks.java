package org.permaformula.validation;

import java.io.IOException;
import java.io.UncheckedIOException;

import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolationException;
import org.permaformula.rendering.ApiWorkService;
import org.springframework.web.server.ResponseStatusException;

final class ConstraintChecks {
    private ConstraintChecks() {
    }

    static boolean check(ApiWorkService work, ConstraintValidatorContext context, Runnable check) {
        try {
            if (work == null) {
                check.run();
            } else {
                work.prepare(() -> {
                    check.run();
                    return null;
                });
            }
            return true;
        } catch (ConstraintViolationException invalid) {
            context.disableDefaultConstraintViolation();
            invalid.getConstraintViolations()
                .forEach(violation -> context
                    .buildConstraintViolationWithTemplate(violation.getMessage())
                    .addConstraintViolation());
            return false;
        } catch (ResponseStatusException failure) {
            throw new ValidationExecutionException(failure);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
