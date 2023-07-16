package org.permmath.exception.model;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class GeneralErrorResponse extends ErrorResponse implements WithStacktrace {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<String> stacktrace;

    public GeneralErrorResponse(UUID id, long timestamp, String path, String exception,
                                String messageKey, String message, String localizedMessage,
                                List<String> stacktrace) {
        super(id, timestamp, path, exception, messageKey, message, localizedMessage);
        this.stacktrace = stacktrace;
    }

}

