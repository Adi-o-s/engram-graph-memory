package com.memora.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Every test run starts from an empty schema, which also proves the migrations work from scratch. */
@TestConfiguration
public class TestFlywayConfig {

    @Bean
    @ConditionalOnProperty(name = "memora.test.clean-db", havingValue = "true")
    FlywayMigrationStrategy cleanThenMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
