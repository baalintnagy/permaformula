package org.permmath.rendering;

public class RenderException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Kind {

        INVALID, OVERLOADED, DEADLINE, CANCELLED, FAILED
    }

    private final Kind kind;

    public RenderException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public RenderException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
