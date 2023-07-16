package org.permmath.exception.model;


import java.io.Serial;
import java.util.List;
import java.util.UUID;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class ConstraintViolationErrorResponse extends ErrorResponse {

    @Serial
    private static final long serialVersionUID = 7058895985869026606L;

    private List<ConstraintViolationData> constraintViolations;

    public ConstraintViolationErrorResponse(UUID id, long timestamp, String path, String exception,
                                            String messageKey,
                                            List<ConstraintViolationData> constraintViolations) {

        super(id, timestamp, path, exception, messageKey, messageKey, messageKey);
        this.constraintViolations = constraintViolations;
    }
}
