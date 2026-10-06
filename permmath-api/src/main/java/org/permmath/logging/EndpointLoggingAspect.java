package org.permmath.logging;

import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EndpointLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(EndpointLoggingAspect.class);


    @Around("@within(org.springframework.web.bind.annotation.RestController) && execution(public * org.permmath..*(..))")
    public Object logEndpoint(ProceedingJoinPoint invocation) throws Throwable {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return invocation.proceed();
        }
        var request = servlet.getRequest();
        var method = request.getMethod();
        var path = request.getRequestURI();
        var handler = invocation.getSignature().toShortString();
        long started = System.nanoTime();
        log.debug("event=endpoint_started method={} path={} handler={}", method, path, handler);
        try {
            var result = invocation.proceed();
            int status = result instanceof ResponseEntity<?> response ? response.getStatusCode().value()
                    : servlet.getResponse() == null ? 200 : servlet.getResponse().getStatus();
            log.info("event=endpoint_completed method={} path={} handler={} status={} durationMs={}",
                    method, path, handler, status, elapsed(started));
            return result;
        } catch (Throwable failure) {
            // Exception handling determines the HTTP status after the controller invocation fails.
            log.warn("event=endpoint_failed method={} path={} handler={} errorType={} durationMs={}",
                    method, path, handler, failure.getClass().getSimpleName(), elapsed(started));
            throw failure;
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
