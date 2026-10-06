package org.permmath.rendering;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class ApiDeadlineFilter extends OncePerRequestFilter {
    private final ApiWorkProperties properties;

    public ApiDeadlineFilter(ApiWorkProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try (@SuppressWarnings("unused")
        var deadline = WorkDeadline.open(properties.timeout())) {
            chain.doFilter(request, response);
        }
    }
}
