package org.permaformula.validation;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.stream.Collectors;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/** Applies record-component constraints even when a record is constructed outside Spring. */
public final class RecordValidation {
    private static final ClassValue<Constructor<?>> CONSTRUCTORS = new ClassValue<>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
            QuickValidator.validate(
                type.isRecord(),
                () -> new IllegalArgumentException("Constructor validation requires a record"));
            Class<?>[] parameters = Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getType)
                .toArray(Class<?>[]::new);
            try {
                return type.getDeclaredConstructor(parameters);
            } catch (NoSuchMethodException missing) {
                throw new IllegalStateException("Record has no canonical constructor", missing);
            }
        }
    };

    // Configuration constructors run before Spring can supply its managed Validator.
    // Keep one provider for the process, rather than building a factory per construction.
    private static final class Provider {
        private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
        private static final Validator VALIDATOR = FACTORY.getValidator();

        static {
            Runtime.getRuntime().addShutdownHook(
                new Thread(FACTORY::close, "record-validation-shutdown"));
        }
    }

    private RecordValidation() {
    }

    public static void requireValid(Class<?> recordType, Object... arguments) {
        String errors = Provider.VALIDATOR.forExecutables()
            .validateConstructorParameters(CONSTRUCTORS.get(recordType), arguments)
            .stream()
            .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
            .sorted()
            .collect(Collectors.joining("; "));
        if (!errors.isEmpty()) {
            // Preserve construction-time IllegalArgumentException without reflecting rejected
            // values.
            throw new IllegalArgumentException(errors);
        }
    }
}
