package org.permaformula.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

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
        var failure = QuickValidator.violation("Invalid input");
        QuickValidator.validate(true, () -> {
            calls.incrementAndGet();
            return failure;
        });
        assertEquals(0, calls.get());
        assertSame(failure, assertThrows(ConstraintViolationException.class,
            () -> QuickValidator.validate(false, () -> {
                calls.incrementAndGet();
                return failure;
            })));
        assertEquals(1, calls.get());
        Object value = new Object();
        assertSame(value, QuickValidator.notNull(value));
        assertSame(value, QuickValidator.orElse(null, value));
        assertThrows(ConstraintViolationException.class, () -> QuickValidator.notNull(null));
    }

    @Test
    void sharedExceptionHandlerProducesStructured400AndPreservesInternalFailures()
        throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new Probe())
            .setControllerAdvice(new LocalExceptionHandler()).build();
        mvc.perform(get("/quick-validator-input"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.violations[0].property").value(""))
            .andExpect(jsonPath("$.violations[0].message").value("Safe rule detail"))
            .andExpect(jsonPath("$.violations[0].invalidValue").doesNotExist())
            .andExpect(jsonPath("$.id").isString())
            .andExpect(jsonPath("$.timestamp").isNumber());
        mvc.perform(get("/quick-validator-state"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
    }

    @RestController
    static class Probe {
        @GetMapping("/quick-validator-input")
        public void invalidInput() {
            QuickValidator.validate(false, () -> QuickValidator.violation("Safe rule detail"));
        }

        @GetMapping("/quick-validator-state")
        public void internalFailure() {
            QuickValidator.validate(false, () -> new IllegalStateException("Private detail"));
        }
    }
}
