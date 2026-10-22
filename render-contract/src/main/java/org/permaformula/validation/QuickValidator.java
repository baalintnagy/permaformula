package org.permaformula.validation;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.metadata.ConstraintDescriptor;

/** Lazy predicates shared by API validation and internal invariant checks. */
public final class QuickValidator {
    public static void validate(boolean condition, Supplier<? extends RuntimeException> failure) {
        if (!condition) {
            throw failure.get();
        }
    }

    public static void validate(boolean condition, String message) {
        validate(condition,
            () -> violation(message));
    }

    public static <T> T notNull(T value, Supplier<? extends RuntimeException> failure) {
        validate(value != null,
            failure);
        return value;
    }

    public static <T> T notNull(T value) {
        return notNull(value, "A required value is missing");
    }

    public static <T> T notNull(T value, String message) {
        return notNull(value,
            () -> violation(message));
    }

    /** Returns the first non-null value, or throws if none is available. */
    @SafeVarargs
    public static <T> T coalesce(T value, T... fallbacks) {
        if (value != null) {
            return value;
        }
        for (T fallback : notNull(fallbacks)) {
            if (fallback != null) {
                return fallback;
            }
        }
        return notNull(value);
    }

    /** Obtains and validates the fallback only when the value is null. */
    public static <T> T coalesce(T value, Supplier<? extends T> fallback) {
        return value != null ? value
            : notNull(notNull(fallback)
                .get());
    }

    /** Contains application-authored rules, never rejected input or parser diagnostics. */
    public static ConstraintViolationException violation(String message) {
        return new ConstraintViolationException(message, Set.of(new RuleViolation(message)));
    }

    private record RuleViolation(String message) implements ConstraintViolation<Object> {
        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public String getMessageTemplate() {
            return message;
        }

        @Override
        public Object getRootBean() {
            return null;
        }

        @Override
        public Class<Object> getRootBeanClass() {
            return Object.class;
        }

        @Override
        public Object getLeafBean() {
            return null;
        }

        @Override
        public Object[] getExecutableParameters() {
            return null;
        }

        @Override
        public Object getExecutableReturnValue() {
            return null;
        }

        @Override
        public Path getPropertyPath() {
            return new RootPath();
        }

        @Override
        public Object getInvalidValue() {
            return null;
        }

        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return null;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            return type.cast(this);
        }
    }

    private record RootPath() implements Path {
        @Override
        public Iterator<Node> iterator() {
            return Collections.emptyIterator();
        }

        @Override
        public String toString() {
            return "";
        }
    }

    private QuickValidator() {
    }
}
