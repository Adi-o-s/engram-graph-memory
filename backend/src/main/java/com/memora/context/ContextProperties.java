package com.memora.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code retrieval}: {@code graph} (default) reads context candidates from FalkorDB by traversal; {@code hybrid}
 * adds PostgreSQL keyword matches after the graph's; {@code keyword} is the original PostgreSQL-only path.
 */
@ConfigurationProperties(prefix = "memora.context")
public record ContextProperties(int defaultMaxChars, int defaultMaxMemories, int defaultMaxMessages, String retrieval) {

    public ContextProperties {
        retrieval = retrieval == null || retrieval.isBlank() ? "graph" : retrieval.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
