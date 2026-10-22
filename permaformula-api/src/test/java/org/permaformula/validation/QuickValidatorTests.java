package org.permaformula.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.permaformula.validation.QuickValidator.coalesce;
import static org.permaformula.validation.QuickValidator.notNull;
import static org.permaformula.validation.QuickValidator.validate;
import static org.permaformula.validation.QuickValidator.violation;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import jakarta.validation.ConstraintViolationException;
import org.boava.exception.LocalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class QuickValidatorTests {
    @Test
    void failureSupplierIsLazyAndTheSuppliedExceptionIsPreserved() {
        var calls = new AtomicInteger();
        var failure = violation("Invalid input");
        validate(true,
            () -> {
                calls.incrementAndGet();
                return failure;
            });
        assertEquals(0, calls.get());
        assertSame(failure, assertThrows(ConstraintViolationException.class,
            () -> validate(false,
                () -> {
                    calls.incrementAndGet();
                    return failure;
                })));
        assertEquals(1, calls.get());
        Object value = new Object();
        assertSame(value, notNull(value));
        assertSame(value, coalesce(null, value));
        assertThrows(ConstraintViolationException.class,
            () -> notNull(null));
    }

    @Test
    void coalesceReturnsFirstNonNullValueAndRejectsMissingValues() {
        Object first = new Object();
        Object second = new Object();
        assertSame(first, coalesce(first));
        assertSame(first, coalesce(first, second));
        assertSame(first, coalesce(null, null, first, second));
        assertSame(second, coalesce(null, null, second));
        assertThrows(ConstraintViolationException.class, () -> coalesce(null));
        assertThrows(ConstraintViolationException.class,
            () -> coalesce(null, null, (Object) null));
    }

    @Test
    void coalesceEvaluatesFallbackOnlyWhenNeededAndValidatesItsResult() {
        Object value = new Object();
        var calls = new AtomicInteger();
        Supplier<Object> fallback = () -> {
            calls.incrementAndGet();
            return value;
        };
        assertSame(value, coalesce(value, fallback));
        assertEquals(0, calls.get());
        assertSame(value, coalesce(null, fallback));
        assertEquals(1, calls.get());
        assertThrows(ConstraintViolationException.class, () -> coalesce(null, () -> null));
        assertSame(value, coalesce(value, (Supplier<Object>) null));
        assertThrows(ConstraintViolationException.class,
            () -> coalesce(null, (Supplier<Object>) null));
        var failure = new IllegalStateException("Cannot obtain default");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> coalesce(null, () -> {
                throw failure;
            })));
    }

    @Test
    void sharedExceptionHandlerProducesStructured400AndPreservesInternalFailures()
        throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new LocalExceptionHandler())
            .build();
        mvc.perform(get("/quick-validator-input"))
            .andExpect(status()
                .isBadRequest())
            .andExpect(jsonPath("$.violations[0].property")
                .value(""))
            .andExpect(jsonPath("$.violations[0].message")
                .value("Safe rule detail"))
            .andExpect(jsonPath("$.violations[0].invalidValue")
                .doesNotExist())
            .andExpect(jsonPath("$.id")
                .isString())
            .andExpect(jsonPath("$.timestamp")
                .isNumber());
        mvc.perform(get("/quick-validator-state"))
            .andExpect(status()
                .isInternalServerError())
            .andExpect(jsonPath("$.detail")
                .value("An unexpected error occurred."));
    }

    @RestController
    static class Probe {
        @GetMapping("/quick-validator-input")
        public void invalidInput() {
            validate(false, "Safe rule detail");
        }

        @GetMapping("/quick-validator-state")
        public void internalFailure() {
            validate(false,
                () -> new IllegalStateException("Private detail"));
        }
    }
}
