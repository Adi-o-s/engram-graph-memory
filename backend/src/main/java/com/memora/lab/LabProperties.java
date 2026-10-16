package com.memora.lab;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.lab")
public record LabProperties(int maxThreads, int maxOperations) {
}
