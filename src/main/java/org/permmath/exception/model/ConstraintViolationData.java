package org.permmath.exception.model;

import java.io.Serial;
import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@EqualsAndHashCode
@ToString
public class ConstraintViolationData implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String message;

    private final String defaultMessage;

    private final String propertyPath;

    private final Serializable invalidValue;

}

