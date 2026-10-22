package org.permaformula;

import java.util.concurrent.Semaphore;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Hold a library permit through synchronous serialization and socket writes. */
@Configuration(proxyBeanMethods = false)
public class LibraryAdmissionConfiguration implements WebMvcConfigurer {
    static final class Admission implements HandlerInterceptor {
        private final Semaphore slots = new Semaphore(2);
        private final String attribute = Admission.class.getName();

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
            Object handler) {
            if (!(handler instanceof HandlerMethod method)
                || !LibraryController.class.isAssignableFrom(method.getBeanType())
                || !(method.getMethod()
                    .getName()
                    .equals("list")
                    || method.getMethod()
                        .getName()
                        .equals("detail"))) {
                return true;
            }
            if (!slots.tryAcquire()) {
                response.setHeader("Retry-After", "1");
                response.setHeader("Cache-Control", "no-store");
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Library busy; retry shortly");
            }
            request.setAttribute(attribute, Boolean.TRUE);
            return true;
        }

        @Override
        public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception failure) {
            if (Boolean.TRUE.equals(request.getAttribute(attribute))) {
                request.removeAttribute(attribute);
                slots.release();
            }
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Admission());
    }
}
