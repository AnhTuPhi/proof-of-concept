package com.demo.deployment.shutdown;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Counts in-flight HTTP requests. The shutdown listener uses this to know
 * when it's safe to terminate. Actuator endpoints are excluded so the
 * shutdown probe traffic itself doesn't count.
 */
@Component
public class InFlightRequestFilter extends OncePerRequestFilter {

    private final InFlightRequestTracker tracker;

    public InFlightRequestFilter(InFlightRequestTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        tracker.begin();
        try {
            chain.doFilter(req, res);
        } finally {
            tracker.end();
        }
    }
}
