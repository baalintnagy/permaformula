package org.permmath.exception.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@JsonPropertyOrder({
        "id",
        "timestamp",
        "timestampEx",
        "path",
        "exception",
        "messageKey",
        "message",
        "localizedMessage",
        "stacktrace"
})
@AllArgsConstructor
@Getter
@EqualsAndHashCode
@ToString
public abstract class ErrorResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 5790241377640297711L;

    protected final UUID id;

    protected final long timestamp;

    public Instant getTimestampEx() {
        return Instant.ofEpochMilli(timestamp);
    }

    protected final String path;

    protected final String exception;

    protected final String messageKey;

    protected final String message;

    protected final String localizedMessage;

}
