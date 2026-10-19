package org.permaformula.validation;

import jakarta.validation.ValidationException;
import org.springframework.web.server.ResponseStatusException;

/** Preserve operational failures without the provider wrapping them as validator bugs. */
public final class ValidationExecutionException extends ValidationException {
    private static final long serialVersionUID = 1L;

    public ValidationExecutionException(ResponseStatusException cause) {
        super("Validation execution could not complete", cause);
    }
}
