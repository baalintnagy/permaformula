package org.permaformula.validation;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

@Documented
@Constraint(validatedBy = ComposeValidator.class)
@Target({ METHOD })
@Retention(RUNTIME)
public @interface ValidCompose {
    String message() default "Invalid compose";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
