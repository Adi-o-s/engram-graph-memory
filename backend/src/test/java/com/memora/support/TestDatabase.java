package com.memora.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Where integration tests get PostgreSQL from:
 * <ol>
 *   <li>{@code MEMORA_TEST_DB_URL} (+ {@code MEMORA_TEST_DB_USER}, {@code MEMORA_TEST_DB_PASSWORD}): an existing
 *       database, e.g. the Docker Compose one or a local install. The role must not be a superuser, or
 *       row-level security is bypassed and the RLS tests fail. The database is cleaned and re-migrated.</li>
 *   <li>otherwise Testcontainers starts {@code postgres:16-alpine} (requires Docker) and creates a
 *       non-superuser application role inside it.</li>
 * </ol>
 */
public final class TestDatabase {

    public static final String URL;
    public static final String USER;
    public static final String PASSWORD;

    static {
        String external = System.getenv("MEMORA_TEST_DB_URL");
        if (external != null && !external.isBlank()) {
            URL = external;
            USER = env("MEMORA_TEST_DB_USER", "memora");
            PASSWORD = env("MEMORA_TEST_DB_PASSWORD", "memora");
        } else {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("postgres").withUsername("postgres").withPassword("postgres");
            container.start();
            try (Connection c = DriverManager.getConnection(container.getJdbcUrl(), "postgres", "postgres");
                 Statement s = c.createStatement()) {
                s.execute("CREATE ROLE memora_app LOGIN PASSWORD 'memora_app'");
                s.execute("CREATE DATABASE memora_it OWNER memora_app");
            } catch (Exception e) {
                throw new IllegalStateException("Could not prepare the test database", e);
            }
            URL = container.getJdbcUrl().replace("/postgres", "/memora_it");
            USER = "memora_app";
            PASSWORD = "memora_app";
        }
    }

    private TestDatabase() {
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
