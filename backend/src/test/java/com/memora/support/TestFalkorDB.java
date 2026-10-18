package com.memora.support;

import java.net.URI;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Where integration tests get FalkorDB from:
 * <ol>
 *   <li>{@code MEMORA_TEST_FALKORDB_URL} (e.g. {@code redis://localhost:6379}): an existing FalkorDB, such as the
 *       Docker Compose one. Every tenant has its own graph and test tenants get fresh ids, so runs do not collide.</li>
 *   <li>otherwise Testcontainers starts {@code falkordb/falkordb} (requires Docker).</li>
 * </ol>
 */
public final class TestFalkorDB {

    public static final String IMAGE = "falkordb/falkordb:v4.14.6";
    public static final String HOST;
    public static final int PORT;
    /** Set when the tests started the container themselves (restart tests need it). */
    public static final GenericContainer<?> CONTAINER;

    static {
        String external = System.getenv("MEMORA_TEST_FALKORDB_URL");
        if (external != null && !external.isBlank()) {
            URI uri = URI.create(external.contains("://") ? external : "redis://" + external);
            HOST = uri.getHost();
            PORT = uri.getPort() > 0 ? uri.getPort() : 6379;
            CONTAINER = null;
        } else {
            GenericContainer<?> c = new GenericContainer<>(IMAGE).withExposedPorts(6379)
                    .withEnv("BROWSER", "0")
                    .waitingFor(Wait.forListeningPort());
            c.start();
            CONTAINER = c;
            HOST = c.getHost();
            PORT = c.getMappedPort(6379);
        }
    }

    private TestFalkorDB() {
    }
}
