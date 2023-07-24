package org.permmath.exception;

import static java.lang.System.currentTimeMillis;
import static java.util.UUID.randomUUID;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.web.context.request.RequestContextHolder.getRequestAttributes;

import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.ValidationException;

import org.permmath.exception.configuration.ExceptionConfigurationProperties;
import org.permmath.exception.model.ConstraintViolationData;
import org.permmath.exception.model.ConstraintViolationErrorResponse;
import org.permmath.exception.model.GeneralErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    public static final String REQUEST_PATH_UNAVAILABLE = "N/A";
    public static final String UNEXPECTED_ERROR_MESSAGE_KEY = "{unexpected.error}";
    public static final String VALIDATION_ERROR_MESSAGE_KEY = "{validation.error}";
    public static final String DATABASE_MESSAGE_KEY = "{database.error}";

    @Autowired
    private ExceptionConfigurationProperties exceptionConfigurationProperties;

    @ExceptionHandler(Exception.class)
    @ResponseStatus(INTERNAL_SERVER_ERROR)
    @ResponseBody
    public GeneralErrorResponse handleGeneralError(Exception e) {
        UUID id = randomUUID();
        log.error("General error [{}]", id, e);

        return new GeneralErrorResponse(
                id,
                currentTimeMillis(),
                getCurrentRequestPath(),
                mapExceptionClass(e),
                UNEXPECTED_ERROR_MESSAGE_KEY,
                mapExceptionMessage(e),
                mapLocalizedExceptionMessage(e),
                mapStacktrace(e));
    }

    @ExceptionHandler({
            ValidationException.class,
            HttpMessageConversionException.class,
            IllegalArgumentException.class,
            URISyntaxException.class
    })
    @ResponseStatus(BAD_REQUEST)
    @ResponseBody
    public GeneralErrorResponse handleValidationOrConversion(Exception e) {
        UUID id = randomUUID();
        log.warn("Validation or HTTP-conversion error [{}]", id, e);

        return new GeneralErrorResponse(
                id,
                currentTimeMillis(),
                getCurrentRequestPath(),
                mapExceptionClass(e),
                VALIDATION_ERROR_MESSAGE_KEY,
                mapExceptionMessage(e),
                mapLocalizedExceptionMessage(e),
                mapStacktrace(e));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(BAD_REQUEST)
    @ResponseBody
    public ConstraintViolationErrorResponse handleConstraintViolationError(ConstraintViolationException e) {
        UUID id = randomUUID();
        log.warn("Constraint-violation error [{}]", id, e);

        return new ConstraintViolationErrorResponse(
                id,
                currentTimeMillis(),
                getCurrentRequestPath(),
                mapExceptionClass(e),
                VALIDATION_ERROR_MESSAGE_KEY,
                e.getConstraintViolations()
                        .stream()
                        .map(GlobalExceptionHandler::mapConstraintViolation)
                        .toList());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(BAD_REQUEST)
    @ResponseBody
    public ConstraintViolationErrorResponse handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        UUID id = randomUUID();
        log.warn("Method-argument-validation error [{}]", id, e);

        return new ConstraintViolationErrorResponse(
                id,
                currentTimeMillis(),
                getCurrentRequestPath(),
                mapExceptionClass(e),
                VALIDATION_ERROR_MESSAGE_KEY,
                e.getBindingResult().getAllErrors()
                        .stream()
                        .filter(error -> error.contains(ConstraintViolation.class))
                        .map(error -> error.unwrap(ConstraintViolation.class))
                        .map(GlobalExceptionHandler::mapConstraintViolation)
                        .toList());
    }

    protected String mapExceptionMessage(Exception e) {
        return getDataFromException(exceptionConfigurationProperties.isAllowMessage(), e, Exception::getMessage);
    }

    protected String mapLocalizedExceptionMessage(Exception e) {
        return getDataFromException(exceptionConfigurationProperties.isAllowLocalizedMessage(), e, Exception::getLocalizedMessage);
    }

    protected String mapExceptionClass(Exception e) {
        return getDataFromException(exceptionConfigurationProperties.isAllowExceptionClass(), e, ex -> ex.getClass().getName());
    }

    protected List<String> mapStacktrace(Exception e) {
        return getDataFromException(exceptionConfigurationProperties.isAllowStacktrace(), e,
                ex -> Arrays.stream(ex.getStackTrace())
                        .map(Object::toString)
                        .toList());
    }

    protected static <T> T getDataFromException(boolean allowed, Exception e, Function<Exception, T> getter) {
        if (!allowed) {
            return null;
        }
        return getter.apply(e);
    }

    protected static ConstraintViolationData mapConstraintViolation(ConstraintViolation<?> cv) {
        return new ConstraintViolationData(
                cv.getMessageTemplate(),
                cv.getMessage(),
                getConstraintViolationAttribute(cv, ConstraintViolation::getPropertyPath),
                getConstraintViolationAttribute(cv, ConstraintViolation::getInvalidValue));
    }

    private static <T, C extends ConstraintViolation<T>> String getConstraintViolationAttribute(
            C cv,
            Function<C, Object> attributeGetter) {
        return Optional.of(cv)
                .map(attributeGetter)
                .map(Object::toString)
                .orElse(null);
    }

    protected String getCurrentRequestPath() {
        try {
            var requestAttributes = getRequestAttributes();
            var requestUri = Optional.ofNullable(requestAttributes)
                    .filter(ServletRequestAttributes.class::isInstance)
                    .map(ServletRequestAttributes.class::cast)
                    .map(ServletRequestAttributes::getRequest)
                    .map(HttpServletRequest::getRequestURI);
            if (requestUri.isPresent()) {
                return requestUri.get();
            }
            log.warn("Could not access request-path from {}", requestAttributes);
        }
        catch (Exception e) {
            log.error("Could not access request-path", e);
        }
        return REQUEST_PATH_UNAVAILABLE;
    }

}
