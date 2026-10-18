package com.memora.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.memora.security.TenantContext;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import com.memora.support.TestFalkorDB;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The graph survives a FalkorDB restart ("remember across sessions"). Needs control of the FalkorDB container:
 * the Testcontainers one, or a named Docker container given in {@code MEMORA_TEST_FALKORDB_CONTAINER}.
 */
class GraphRestartIT extends IntegrationTest {

    @Autowired
    GraphClient graph;
    @Autowired
    GraphProjector projector;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void theGraphIsStillThereAfterFalkorDbRestarts() throws Exception {
        String container = System.getenv("MEMORA_TEST_FALKORDB_CONTAINER");
        assumeThat(TestFalkorDB.CONTAINER != null || (container != null && !container.isBlank()))
                .as("needs a FalkorDB container this test may restart").isTrue();
        UUID acme = TenantContext.callAsSystem(() -> jdbc.queryForObject("SELECT id FROM tenants WHERE slug = 'acme'", UUID.class));
        String g = TenantGraphs.nameFor(acme);
        String content = "Procedure: survive restarts " + UUID.randomUUID();
        Response r = as(ALICE).post("/api/v1/memories", Map.of("type", "PROCEDURAL", "content", content));
        String id = r.body().path("memory").path("id").asText();
        projector.catchUp(acme);
        String count = "MATCH (m:Memory {id: $id}) RETURN m.content AS content";
        assertThat(graph.read(g, "test", count, Map.of("id", id))).hasSize(1);

        if (TestFalkorDB.CONTAINER != null) {
            TestFalkorDB.CONTAINER.getDockerClient().restartContainerCmd(TestFalkorDB.CONTAINER.getContainerId()).exec();
        } else {
            Process p = new ProcessBuilder("docker", "restart", container).inheritIO().start();
            assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
            assertThat(p.exitValue()).isZero();
        }

        List<Map<String, Object>> rows = null;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                rows = graph.read(g, "test", count, Map.of("id", id));
                break;
            } catch (RuntimeException e) {
                Thread.sleep(250); // still loading the dataset
            }
        }
        assertThat(rows).as("graph readable after restart").isNotNull().hasSize(1);
        assertThat(rows.get(0).get("content")).isEqualTo(content);
    }
}
