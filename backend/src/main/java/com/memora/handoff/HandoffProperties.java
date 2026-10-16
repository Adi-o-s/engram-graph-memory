package com.memora.handoff;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.handoff")
public record HandoffProperties(long leaseSeconds, long recoveryIntervalMs) {
}
