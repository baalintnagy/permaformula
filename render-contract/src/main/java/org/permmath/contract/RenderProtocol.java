package org.permmath.contract;

public final class RenderProtocol {
    public static final int MAX_CONTENT_BYTES = 16 * 1024 * 1024;
    public static final int MAX_MESSAGE_BYTES = MAX_CONTENT_BYTES + 64 * 1024;
    private RenderProtocol() {}
}
