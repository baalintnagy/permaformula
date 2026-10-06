package org.permmath.rendering;

import java.time.Duration;
import org.permmath.contract.v1.RenderRequest;
import org.permmath.contract.v1.RenderResponse;

/** Transport-independent execution port for gRPC and a future queue consumer. */
public interface RenderJobExecutor {
    RenderResponse execute(RenderRequest request, Duration budget) throws RenderException;
}
