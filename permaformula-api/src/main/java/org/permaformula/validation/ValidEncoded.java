package org.permaformula.validation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = EncodedValidator.class)
@Target({ METHOD, PARAMETER, FIELD, ANNOTATION_TYPE, TYPE_USE })
@Retention(RUNTIME)
public @interface ValidEncoded {
    String message() default "Invalid encoded";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
