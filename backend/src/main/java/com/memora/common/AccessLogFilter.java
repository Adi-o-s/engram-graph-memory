package com.memora.common;

import com.memora.security.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One structured log line per API request (method, route template, status, duration, tenant) and a
 * latency sample for the dashboard. Bodies, headers and tokens are never logged.
 * Registered as a servlet filter, so it runs inside the security chain's tenant scope.
 */
@Component
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("memora.access");

    private final RequestMetrics metrics;

    public AccessLogFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            double millis = (System.nanoTime() - start) / 1_000_000.0;
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = request.getMethod() + " " + (pattern != null ? pattern : request.getRequestURI());
            UUID tenantId = TenantContext.tenantId();
            if (tenantId != null) {
                metrics.record(tenantId, route, response.getStatus(), millis);
            }
            log.info("method={} route=\"{}\" status={} durationMs={}", request.getMethod(), route, response.getStatus(),
                    String.format("%.1f", millis));
        }
    }
}
