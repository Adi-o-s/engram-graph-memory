package com.memora.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConcurrencyLabIT extends IntegrationTest {

    @Test
    void everyScenarioKeepsItsInvariants() {
        ApiClient alice = as(ALICE);
        for (String scenario : new String[]{"TASK_COUNTER", "MESSAGE_APPEND", "MEMORY_WRITES"}) {
            JsonNode r = alice.post("/api/v1/lab/runs", Map.of("scenario", scenario, "threads", 8, "operations", 120)).body();
            assertThat(r.path("allInvariantsPassed").asBoolean()).as(scenario + ": " + r).isTrue();
            assertThat(r.path("succeeded").asLong()).as(scenario).isEqualTo(120);
            assertThat(r.path("latency").path("p95Ms").asDouble()).isPositive();
        }
        assertThat(alice.get("/api/v1/lab/runs").body().size()).isGreaterThanOrEqualTo(3);
        assertThat(as(BOB).get("/api/v1/lab/runs").body().findValuesAsText("runId")).isEmpty();
    }

    @Test
    void limitsAreValidated() {
        ApiClient alice = as(ALICE);
        assertThat(alice.post("/api/v1/lab/runs", Map.of("scenario", "TASK_COUNTER", "threads", 500, "operations", 10)).status()).isEqualTo(400);
        assertThat(alice.post("/api/v1/lab/runs", Map.of("scenario", "NOPE", "threads", 1, "operations", 1)).status()).isEqualTo(400);
    }
}
