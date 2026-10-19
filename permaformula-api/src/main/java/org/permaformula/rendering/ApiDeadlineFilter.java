package org.permaformula.rendering;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public final class ApiDeadlineFilter extends OncePerRequestFilter {
    private final ApiWorkProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain)
        throws ServletException, IOException {
        try (WorkDeadline _ = WorkDeadline.open(properties.timeout())) {
            chain.doFilter(request, response);
        }
    }
}
