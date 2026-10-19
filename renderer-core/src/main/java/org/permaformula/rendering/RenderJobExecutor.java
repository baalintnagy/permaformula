package org.permaformula.rendering;

import java.time.Duration;

import org.permaformula.contract.v1.RenderRequest;
import org.permaformula.contract.v1.RenderResponse;

/** Transport-independent execution port for gRPC and a future queue consumer. */
public interface RenderJobExecutor {
    RenderResponse execute(RenderRequest request, Duration budget) throws RenderException;
}
