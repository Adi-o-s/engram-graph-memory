package com.memora.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.context")
public record ContextProperties(int defaultMaxChars, int defaultMaxMemories, int defaultMaxMessages) {
}
