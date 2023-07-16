package org.permmath.exception.model;

import java.io.Serial;
import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ConstraintViolationData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String message;

    private String defaultMessage;

    private String propertyPath;

    private Serializable invalidValue;

}

