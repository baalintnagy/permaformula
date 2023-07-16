package org.permmath.exception.model;

import java.util.List;

public interface WithStacktrace {

    List<String> getStacktrace();

    void setStacktrace(List<String> stacktrace);
}
