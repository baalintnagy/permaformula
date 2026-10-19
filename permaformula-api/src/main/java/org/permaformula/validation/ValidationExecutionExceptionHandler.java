package org.permaformula.validation;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.boava.exception.LocalExceptionHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ValidationExecutionExceptionHandler {
    private final LocalExceptionHandler handler;

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Object> handle(ValidationException failure, WebRequest request)
        throws Exception {
        if (failure instanceof ConstraintViolationException violations) {
            return handler.handleConstraintViolation(violations, request);
        }
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ValidationExecutionException execution
                && execution.getCause() instanceof ResponseStatusException status) {
                return handler.handleException(status, request);
            }
            cause = cause.getCause();
        }
        return handler.handleUnhandledException(failure, request);
    }
}
