package org.permaformula.rendering;

import org.permaformula.contract.RenderProtocol;

/** Operational message bounds; the API validates rendering inputs. */
public final class RenderLimits {
    public static final int MAX_CONTENT_BYTES = RenderProtocol.MAX_CONTENT_BYTES;
    public static final int MAX_MESSAGE_BYTES = RenderProtocol.MAX_MESSAGE_BYTES;

    private RenderLimits() {
    }
}
