package com.memora.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.seed")
public record DemoProperties(boolean enabled, String demoPassword) {
}
