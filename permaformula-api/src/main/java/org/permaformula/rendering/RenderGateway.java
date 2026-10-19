package org.permaformula.rendering;

import java.io.IOException;

/** API-side rendering port; HTTP endpoints and caching do not depend on gRPC stubs. */
public interface RenderGateway {
    byte[] renderHtml(String preparedDocument, int width) throws IOException;

    byte[] renderFormula(String formula) throws IOException;

    default byte[] renderJpeg(String svg) throws IOException {
        throw new IOException("JPEG conversion is unavailable");
    }
}
