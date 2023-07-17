package org.permmath.exception.model;

import static lombok.AccessLevel.PRIVATE;

import lombok.NoArgsConstructor;

@NoArgsConstructor(access = PRIVATE)
public final class ExceptionHandlerConstants {

    public static final String REQUEST_PATH_UNAVAILABLE = "N/A";
    public static final String UNEXPECTED_ERROR_MESSAGE_KEY = "{unexpected.error}";
    public static final String VALIDATION_ERROR_MESSAGE_KEY = "{validation.error}";
    public static final String DATABASE_MESSAGE_KEY = "{database.error}";
}
