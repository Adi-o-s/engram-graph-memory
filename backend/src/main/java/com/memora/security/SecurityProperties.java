package com.memora.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.security")
public record SecurityProperties(String jwtSecret, Duration tokenTtl, List<String> corsOrigins) {
}
