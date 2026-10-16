package com.memora.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** The judge-facing workflow end to end through the API, including failure and retry. */
class DemoWorkflowIT extends IntegrationTest {

    @Test
    void plannerDeveloperTesterCompleteATaskThroughPersistedHandoffs() {
        ApiClient alice = as(ALICE);
        Response start = alice.post("/api/v1/demo/runs", null);
        assertThat(start.status()).isEqualTo(201);
        String project = start.body().path("project").path("id").asText();
        assertThat(start.body().path("messages").findValuesAsText("outcome")).contains("SUPERSEDED_PREVIOUS");

        assertThat(alice.post("/api/v1/demo/runs/" + project + "/develop", null).status())
                .as("developer cannot start before the planner hands off").isEqualTo(409);

        Response plan = alice.post("/api/v1/demo/runs/" + project + "/plan", null);
        assertThat(plan.text("outcome")).isEqualTo("HANDED_OFF");
        Response develop = alice.post("/api/v1/demo/runs/" + project + "/develop", null);
        assertThat(develop.text("outcome")).isEqualTo("HANDED_OFF");
        assertThat(develop.body().path("details").path("database").asText()).isEqualTo("postgresql");
        assertThat(develop.body().path("details").path("context").asText()).contains("PostgreSQL").doesNotContain("uses MySQL as its database");

        Response firstTest = alice.post("/api/v1/demo/runs/" + project + "/test", null);
        assertThat(firstTest.text("outcome")).isEqualTo("FAILED_RETRYABLE");
        Response secondTest = alice.post("/api/v1/demo/runs/" + project + "/test", null);
        assertThat(secondTest.text("outcome")).isEqualTo("DONE");

        JsonNode status = alice.get("/api/v1/demo/runs/" + project).body();
        assertThat(status.path("task").path("status").asText()).isEqualTo("DONE");
        assertThat(status.path("handoffs")).hasSize(2);
        assertThat(status.path("handoffs").get(1).path("attempts").asInt()).isEqualTo(2);
        assertThat(status.path("events").findValuesAsText("eventType"))
                .contains("handoff_failed", "handoff_retried", "handoff_completed");
        assertThat(status.path("memories").findValuesAsText("type")).contains("EPISODIC", "SEMANTIC", "PROCEDURAL");
        assertThat(status.path("nextStep").asText()).isEqualTo("done");
    }

    @Test
    void stepsAreRetrySafe() {
        ApiClient bob = as(BOB);
        String project = bob.post("/api/v1/demo/runs", null).body().path("project").path("id").asText();
        Response plan1 = bob.post("/api/v1/demo/runs/" + project + "/plan", null);
        Response plan2 = bob.post("/api/v1/demo/runs/" + project + "/plan", null);
        assertThat(plan2.text("taskId")).isEqualTo(plan1.text("taskId"));
        assertThat(plan2.text("handoffId")).isEqualTo(plan1.text("handoffId"));
        assertThat(bob.post("/api/v1/demo/runs/" + project + "/develop", null).text("outcome")).isEqualTo("HANDED_OFF");
        assertThat(bob.post("/api/v1/demo/runs/" + project + "/test?failFirst=false", null).text("outcome")).isEqualTo("DONE");
    }

    @Test
    void demoRunsAreInvisibleToOtherTenants() {
        ApiClient alice = as(ALICE);
        String project = alice.post("/api/v1/demo/runs", null).body().path("project").path("id").asText();
        assertThat(as(BOB).get("/api/v1/demo/runs/" + project).status()).isEqualTo(404);
        assertThat(as(BOB).post("/api/v1/demo/runs/" + project + "/plan", null).status()).isEqualTo(404);
    }
}
