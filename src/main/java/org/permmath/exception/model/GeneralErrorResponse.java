package org.permmath.exception.model;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@EqualsAndHashCode(callSuper = false)
@ToString(callSuper = true)
public class GeneralErrorResponse extends ErrorResponse {

    @Serial
    private static final long serialVersionUID = -5464718470196806419L;

    private final List<String> stacktrace;

    public GeneralErrorResponse(UUID id,
            long timestamp,
            String path,
            String exception,
            String messageKey,
            String message,
            String localizedMessage,
            List<String> stacktrace) {
        super(id, timestamp, path, exception, messageKey, message, localizedMessage);
        this.stacktrace = stacktrace;
    }

}

