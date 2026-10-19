package org.permaformula.rendering;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class WorkOverloadedException extends ResponseStatusException {
    private static final long serialVersionUID = 1L;

    WorkOverloadedException(String reason, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, reason, cause);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "1");
        headers.setCacheControl("no-store");
        return headers;
    }
}
