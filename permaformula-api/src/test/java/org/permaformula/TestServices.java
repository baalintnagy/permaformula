package org.permaformula;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;

/** Standalone tests exercise the production validation advice as well as the service. */
public final class TestServices {
    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    public static <T> T validated(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new MethodValidationInterceptor(VALIDATORS.getValidator()));
        return factory.getProxy();
    }

    private TestServices() {
    }
}
