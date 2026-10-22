package org.permaformula.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.permaformula.validation.QuickValidator.violation;

import java.time.Duration;

import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import org.boava.exception.LocalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.permaformula.rendering.ApiWorkProperties;
import org.permaformula.rendering.ApiWorkService;
import org.permaformula.rendering.WorkDeadline;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.beanvalidation.SpringConstraintValidatorFactory;
import org.springframework.web.context.request.ServletWebRequest;

class ValidationExecutionTests {
    public static class Boundary {
        public void accept(@ValidFormula String formula) {
        }
    }

    @Test
    void constraintFailuresRemain400AndValidatorBugsRemain500() throws Exception {
        var handler = new ValidationExecutionExceptionHandler(new LocalExceptionHandler());
        var request = new ServletWebRequest(new MockHttpServletRequest(),
            new MockHttpServletResponse());
        assertEquals(400, handler.handle(violation("Invalid input"), request)
            .getStatusCode()
            .value());
        assertEquals(500, handler.handle(new ValidationException("Validator bug"), request)
            .getStatusCode()
            .value());
    }

    @Test
    // The deadline resource installs and restores request-local state.
    void methodValidationPreservesOperationalDeadlineStatus() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ApiWorkService.class,
                () -> new ApiWorkService(ApiWorkProperties.defaults()));
            context.refresh();
            try (var factory = Validation.byDefaultProvider()
                .configure()
                .constraintValidatorFactory(new SpringConstraintValidatorFactory(
                    context.getAutowireCapableBeanFactory()))
                .buildValidatorFactory();
                var _ = WorkDeadline.open(Duration.ZERO)) {
                var failure = assertThrows(ValidationException.class,
                    () -> factory.getValidator()
                        .forExecutables()
                        .validateParameters(
                            new Boundary(), Boundary.class.getMethod("accept", String.class),
                            new Object[] { "x" }));
                var handler = new ValidationExecutionExceptionHandler(new LocalExceptionHandler());
                var response = handler.handle(failure, new ServletWebRequest(
                    new MockHttpServletRequest(), new MockHttpServletResponse()));
                assertEquals(504, response.getStatusCode()
                    .value());
            }
        }
    }
}
