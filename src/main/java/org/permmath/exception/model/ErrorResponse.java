package org.permmath.exception.model;

import static lombok.AccessLevel.PROTECTED;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Data
@NoArgsConstructor(access = PROTECTED)
@AllArgsConstructor
public abstract class ErrorResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 5790241377640297711L;

    protected UUID id;

    protected long timestamp;

    public Instant getTimestampEx() {
        return Instant.ofEpochMilli(timestamp);
    }

    protected String path;

    protected String exception;

    protected String messageKey;

    protected String message;

    protected String localizedMessage;

}
