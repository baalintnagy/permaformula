package org.permaformula.validation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = JsonValidator.class)
@Target({ METHOD, PARAMETER, FIELD, ANNOTATION_TYPE, TYPE_USE })
@Retention(RUNTIME)
public @interface ValidJson {
    String message() default "Invalid json";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
