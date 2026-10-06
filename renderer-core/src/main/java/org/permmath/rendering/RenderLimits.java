package org.permmath.rendering;

import org.permmath.contract.v1.RenderRequest;

public final class RenderLimits {

    public static final int MAX_CONTENT_BYTES = org.permmath.contract.RenderProtocol.MAX_CONTENT_BYTES;

    public static final int MAX_MESSAGE_BYTES = org.permmath.contract.RenderProtocol.MAX_MESSAGE_BYTES;

    public static void validate(RenderRequest request) throws RenderException {
        if (request.getRequestId().isBlank() || request.getRequestId().length() > 128 || request.getSerializedSize() > MAX_MESSAGE_BYTES) {
            invalid();
        }
        switch(request.getInputCase()) {
            case HTML ->
                {
                    var html = request.getHtml();
                    if (html.getWidth() < 220 || html.getWidth() > 4000 || html.getDocument().isBlank() || html.getDocumentBytes().size() > MAX_CONTENT_BYTES) {
                        invalid();
                    }
                }
            case FORMULA ->
                {
                    if (request.getFormula().getFormula().isBlank() || request.getFormula().getFormulaBytes().size() > MAX_CONTENT_BYTES) {
                        invalid();
                    }
                    try {
                        org.permmath.contract.ComplexityPolicy.CURRENT.checkMathInput(request.getFormula().getFormula());
                    } catch (IllegalArgumentException rejected) { invalid(); }
                }
            default ->
                invalid();
        }
    }

    private static void invalid() throws RenderException {
        throw new RenderException(RenderException.Kind.INVALID, "Invalid rendering request");
    }
}
