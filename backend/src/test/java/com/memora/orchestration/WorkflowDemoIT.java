package com.memora.orchestration;

import static com.memora.support.Workflows.await;
import static com.memora.support.Workflows.awaitStatus;
import static com.memora.support.Workflows.iterations;
import static com.memora.support.Workflows.start;
import static com.memora.support.Workflows.step;
import static com.memora.support.Workflows.workflowId;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The two primary demonstrations, end to end through the public API: a software-delivery workflow and a
 * research workflow with entirely different agents, both executed by the same engine.
 */
class WorkflowDemoIT extends IntegrationTest {

    static final String GOAL = "Build a full-stack e-commerce website with responsive design, authentication, product search, "
            + "a shopping cart, order management, database persistence, and automated tests.";

    @Test
    void softwareDeliveryRunsInParallelLoopsUntilCoveredWaitsForApprovalAndSucceeds() {
        ApiClient alice = as(ALICE);
        String workflow = workflowId(alice, "fullstack-delivery");
        String runId = start(alice, workflow, Map.of("goal", GOAL, "options", Map.of("pacingMs", 300)));

        JsonNode waiting = awaitStatus(alice, runId, Set.of("WAITING_APPROVAL", "FAILED", "SUCCEEDED"), Duration.ofSeconds(90));
        assertThat(waiting.path("status").asText()).as(com.memora.support.Workflows.summary(waiting)).isEqualTo("WAITING_APPROVAL");

        // Requirements were extracted from the goal.
        JsonNode requirements = step(waiting, "requirements");
        assertThat(requirements.path("output").path("count").asInt()).isEqualTo(7);

        // Architecture and design ran in parallel: each started before the other finished.
        JsonNode architecture = step(waiting, "architecture");
        JsonNode design = step(waiting, "design");
        assertThat(Instant.parse(architecture.path("startedAt").asText())).isBefore(Instant.parse(design.path("finishedAt").asText()));
        assertThat(Instant.parse(design.path("startedAt").asText())).isBefore(Instant.parse(architecture.path("finishedAt").asText()));

        // Frontend waited for both of its dependencies.
        JsonNode frontend = step(waiting, "frontend");
        assertThat(Instant.parse(frontend.path("startedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(architecture.path("finishedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(design.path("finishedAt").asText()));
        assertThat(frontend.path("readyReason").path("dependencies")).hasSize(2);

        // QA found the uncovered requirement ("order management"), remediation ran once, QA passed on iteration 2.
        List<JsonNode> qa = iterations(waiting, "qa");
        assertThat(qa).hasSize(2);
        assertThat(qa.get(0).path("output").path("passed").asBoolean()).isFalse();
        assertThat(qa.get(0).path("output").path("missing").toString()).contains("R5");
        assertThat(iterations(waiting, "fix-gaps")).hasSize(1);
        assertThat(qa.get(1).path("output").path("passed").asBoolean()).isTrue();

        // Conditional routing: security ran (authentication is in scope), payments review was skipped.
        assertThat(step(waiting, "security").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(step(waiting, "payments-compliance").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(step(waiting, "final-review").path("output").path("passed").asBoolean()).isTrue();

        JsonNode handover = step(waiting, "handover");
        assertThat(handover.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        Response approved = alice.post("/api/v1/workflow-runs/" + runId + "/steps/" + handover.path("id").asText() + "/approve",
                Map.of("note", "Looks good"));
        assertThat(approved.status()).as(approved.toString()).isEqualTo(200);

        JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), Duration.ofSeconds(30));
        assertThat(done.path("status").asText()).as(done.path("completion").toString()).isEqualTo("SUCCEEDED");
        assertThat(done.path("completion").path("passed").asBoolean()).isTrue();
        assertThat(done.path("completion").path("criteria")).hasSize(3);

        // Real, inspectable artifacts with checksums.
        JsonNode artifacts = alice.get("/api/v1/workflow-runs/" + runId + "/artifacts").body();
        List<String> names = new ArrayList<>();
        artifacts.forEach(a -> names.add(a.path("name").asText()));
        assertThat(names).contains("requirements.md", "api-contract.json", "design-spec.md", "schema.sql", "gap-resolution.md",
                "qa-report.md", "security-review.md", "final-report.md", "release-notes.md");
        String contractId = null;
        for (JsonNode a : artifacts) {
            if (a.path("name").asText().equals("api-contract.json")) {
                contractId = a.path("id").asText();
            }
        }
        JsonNode contract = alice.get("/api/v1/workflow-artifacts/" + contractId).body();
        assertThat(contract.path("content").asText()).contains("/api/auth/login").contains("\"R2\"");
        assertThat(contract.path("checksum").asText()).hasSize(64);

        // Every step had a task on the board and a real handoff.
        for (JsonNode s : done.path("steps")) {
            assertThat(s.path("taskId").asText()).isNotBlank();
        }
        assertThat(step(done, "backend").path("handoffId").asText()).isNotBlank();

        // Decisions went to shared project memory with provenance.
        JsonNode ws = alice.get("/api/v1/projects/" + done.path("projectId").asText() + "/workspace").body();
        assertThat(ws.path("decisions").toString()).contains("REST over JSON");
    }

    @Test
    void researchWorkflowUsesDifferentAgentsOnTheSameEngineAndDisclosesUnsupportedQuestions() {
        ApiClient alice = as(ALICE);
        String project = null;
        for (JsonNode p : alice.get("/api/v1/projects").body()) {
            if (p.path("slug").asText().equals("search-evaluation")) {
                project = p.path("id").asText();
            }
        }
        assertThat(project).as("demo research project").isNotNull();
        String runId = start(alice, workflowId(alice, "research-report"), Map.of("projectId", project,
                "goal", "Research product search options and compare PostgreSQL full-text search, Elasticsearch and Meilisearch, "
                        + "covering latency, faceted filtering, operational cost and vendor lock-in."));
        JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), Duration.ofSeconds(60));
        assertThat(done.path("status").asText()).as(com.memora.support.Workflows.summary(done)).isEqualTo("SUCCEEDED");
        assertThat(step(done, "collect").path("output").path("count").asInt()).isGreaterThan(0);
        // "vendor lock-in" has no recorded note: the fact check failed once, the report was revised to disclose it.
        List<JsonNode> checks = iterations(done, "fact-check");
        assertThat(checks).hasSize(2);
        assertThat(checks.get(0).path("output").path("passed").asBoolean()).isFalse();
        assertThat(checks.get(1).path("output").path("passed").asBoolean()).isTrue();
        JsonNode artifacts = alice.get("/api/v1/workflow-runs/" + runId + "/artifacts?allVersions=true").body();
        int reportVersions = 0;
        for (JsonNode a : artifacts) {
            if (a.path("name").asText().equals("report.md")) {
                reportVersions++;
            }
        }
        assertThat(reportVersions).isEqualTo(2);
        // Plan and collect are independent: both were roots of the graph.
        assertThat(step(done, "plan").path("dependsOn")).isEmpty();
        assertThat(step(done, "collect").path("dependsOn")).isEmpty();
    }

    @Test
    void classicPlanBuildTestWorkflowStillWorksAsConfiguration() {
        ApiClient alice = as(ALICE);
        String runId = start(alice, workflowId(alice, "plan-build-test"),
                Map.of("goal", "Add a health endpoint that reports database status, responds within 200 ms and hides credentials."));
        JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), Duration.ofSeconds(60));
        assertThat(done.path("status").asText()).as(com.memora.support.Workflows.summary(done)).isEqualTo("SUCCEEDED");
        assertThat(step(done, "test").path("output").path("passed").asBoolean()).isTrue();
        // The seeded Planner, Developer and Tester principals are the ones assigned.
        JsonNode agents = done.path("agents");
        List<String> names = new ArrayList<>();
        agents.forEach(a -> names.add(a.path("name").asText()));
        assertThat(names).contains("Planner", "Developer", "Tester");
        await(alice, runId, r -> true, Duration.ofSeconds(1));
    }
}
