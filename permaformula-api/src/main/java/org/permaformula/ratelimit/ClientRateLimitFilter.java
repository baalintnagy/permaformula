package org.permaformula.ratelimit;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public final class ClientRateLimitFilter extends OncePerRequestFilter {
    private final ClientRateLimits limits;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        ClientRateLimits.Scope scope;
        try {
            scope = limits.open(request);
        } catch (ResponseStatusException denied) {
            response.setStatus(denied.getStatusCode()
                .value());
            denied.getHeaders()
                .forEach(
                    (name, values) -> values.forEach(value -> response.addHeader(name, value)));
            response.setHeader("Cache-Control", "no-store");
            return;
        }
        try (scope) {
            chain.doFilter(request, response);
        }
    }
}
