package com.memora.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FalkorDB connection and projector settings. FalkorDB holds the memory and coordination graph (one graph per
 * tenant); PostgreSQL stays the transactional system of record and feeds the graph through {@code graph_outbox}.
 */
@ConfigurationProperties(prefix = "memora.graph")
public record GraphProperties(String host, Integer port, String password, Long queryTimeoutMs, Long pollIntervalMs,
                              Integer batchSize, Integer maxAttempts, Long catchUpTimeoutMs, Boolean projectorEnabled) {

    public GraphProperties {
        host = host == null || host.isBlank() ? "localhost" : host;
        port = port == null ? 6379 : port;
        password = password == null || password.isBlank() ? null : password;
        queryTimeoutMs = queryTimeoutMs == null ? 5000L : queryTimeoutMs;
        pollIntervalMs = pollIntervalMs == null ? 250L : pollIntervalMs;
        batchSize = batchSize == null ? 200 : Math.max(1, batchSize);
        maxAttempts = maxAttempts == null ? 10 : maxAttempts;
        catchUpTimeoutMs = catchUpTimeoutMs == null ? 3000L : catchUpTimeoutMs;
        projectorEnabled = projectorEnabled == null || projectorEnabled;
    }
}
