package com.memora.orchestration;

import static com.memora.orchestration.WorkflowFixtures.base;
import static com.memora.orchestration.WorkflowFixtures.createAgent;
import static com.memora.orchestration.WorkflowFixtures.createWorkflow;
import static com.memora.orchestration.WorkflowFixtures.echoAgent;
import static com.memora.orchestration.WorkflowFixtures.m;
import static com.memora.orchestration.WorkflowFixtures.spec;
import static com.memora.orchestration.WorkflowFixtures.step;
import static com.memora.orchestration.WorkflowFixtures.unique;
import static com.memora.orchestration.WorkflowFixtures.with;
import static com.memora.support.Workflows.await;
import static com.memora.support.Workflows.awaitStatus;
import static com.memora.support.Workflows.start;
import static com.memora.support.Workflows.step;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tenant isolation, roles, tool authorization, approvals and secret handling for every new resource type. */
class OrchestrationSecurityIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Test
    void anotherTenantCannotSeeOrTouchAgentsWorkflowsRunsStepsOrArtifacts() {
        ApiClient alice = as(ALICE);
        ApiClient bob = as(BOB);
        String echoKey = createAgent(alice, echoAgent(unique("echo")));
        String agentId = com.memora.support.Workflows.agentDefinitionId(alice, echoKey);
        String wf = createWorkflow(alice, unique("secret-wf"), spec(step("a", echoKey, List.of(), m("label", "a", "value", "acme only"))));
        String runId = start(alice, wf, m());
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        String stepId = step(run, "a").path("id").asText();
        String artifactId = alice.get("/api/v1/workflow-runs/" + runId + "/artifacts").body().get(0).path("id").asText();
        String projectId = run.path("projectId").asText();

        for (String path : List.of("/api/v1/agent-definitions/" + agentId, "/api/v1/agent-definitions/" + agentId + "/versions",
                "/api/v1/workflows/" + wf, "/api/v1/workflows/" + wf + "/versions", "/api/v1/workflow-runs/" + runId,
                "/api/v1/workflow-runs/" + runId + "/events", "/api/v1/workflow-runs/" + runId + "/artifacts",
                "/api/v1/workflow-runs/" + runId + "/tool-calls", "/api/v1/workflow-runs/" + runId + "/steps/" + stepId,
                "/api/v1/workflow-artifacts/" + artifactId, "/api/v1/projects/" + projectId + "/workspace")) {
            assertThat(bob.get(path).status()).as("GET " + path).isEqualTo(404);
        }
        assertThat(bob.get("/api/v1/agent-definitions").body().findValuesAsText("key")).doesNotContain(echoKey);
        assertThat(bob.get("/api/v1/workflow-runs").body().path("items").findValuesAsText("id")).doesNotContain(runId);
        Map<String, Object> update = echoAgent(echoKey);
        update.put("expectedLockVersion", 0);
        assertThat(bob.send("PUT", "/api/v1/agent-definitions/" + agentId, update, null).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/agent-definitions/" + agentId + "/duplicate", null).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/workflows/" + wf + "/runs", m()).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/workflow-runs/" + runId + "/cancel", null).status()).isEqualTo(404);
        assertThat(bob.post("/api/v1/workflow-runs/" + runId + "/steps/" + stepId + "/retry", null).status()).isEqualTo(404);
        // Bob cannot point his own workflow at Alice's agent, nor write memory into Alice's run.
        Response foreignAgent = bob.post("/api/v1/workflows", m("name", "Steal " + unique("s"), "spec",
                spec(step("a", echoKey, List.of(), m()))));
        assertThat(foreignAgent.status()).isEqualTo(422);
        Response foreignRunMemory = bob.post("/api/v1/memories", m("type", "SEMANTIC", "content", "Injected note",
                "scope", "RUN", "scopeRef", runId));
        assertThat(foreignRunMemory.status()).isEqualTo(404);
    }

    @Test
    void membersCanRunWorkflowsButNotAdministerThem() {
        ApiClient alice = as(ALICE);
        ApiClient aaron = as(AARON);
        String echoKey = createAgent(alice, echoAgent(unique("echo")));
        Map<String, Object> s = spec(step("a", echoKey, List.of(), m("label", "a")),
                with(step("b", echoKey, List.of("a"), m("label", "b")), "approval", m("required", true, "reason", "Sign-off")));
        String wf = createWorkflow(alice, unique("member"), s);

        assertThat(aaron.post("/api/v1/workflows", m("name", "Mine " + unique("m"), "spec", s)).status()).isEqualTo(403);
        assertThat(aaron.post("/api/v1/workflows/" + wf + "/runs", m("options", m("faults", m("a", m("failAttempts", 1)))))
                .status()).isEqualTo(403);
        String runId = start(aaron, wf, m());
        JsonNode waiting = awaitStatus(aaron, runId, Set.of("WAITING_APPROVAL"), WAIT);
        String gate = step(waiting, "b").path("id").asText();
        assertThat(aaron.post("/api/v1/workflow-runs/" + runId + "/steps/" + gate + "/approve", null).status()).isEqualTo(403);
        assertThat(alice.post("/api/v1/workflow-runs/" + runId + "/steps/" + gate + "/reject", m("note", "Not this time")).status())
                .isEqualTo(200);
        JsonNode rejected = awaitStatus(alice, runId, Set.of("FAILED"), WAIT);
        assertThat(step(rejected, "b").path("error").asText()).contains("Rejected by Alice Admin: Not this time");
        assertThat(step(rejected, "b").path("approval").path("status").asText()).isEqualTo("REJECTED");
    }

    @Test
    void toolsOutsideTheAgentsPolicyAreDeniedAtRuntimeAndAudited() {
        ApiClient alice = as(ALICE);
        Map<String, Object> snoop = base(unique("snoop"), "Snoop " + unique("x"), "Researcher");
        snoop.put("capabilities", List.of("memory.read"));
        snoop.put("tools", List.of("memory.search"));
        snoop.put("memoryPolicy", m("read", List.of("RUN"), "write", List.of()));
        snoop.put("behavior", m("operations", List.of(m("id", "s", "tool", "memory.search",
                "args", m("query", "anything", "scopes", List.of("TENANT"))))));
        String key = createAgent(alice, snoop);
        String runId = start(alice, createWorkflow(alice, unique("snoop"), spec(step("a", key, List.of(), m()))), m());
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        JsonNode a = step(run, "a");
        assertThat(a.path("status").asText()).isEqualTo("FAILED");
        assertThat(a.path("errorKind").asText()).isEqualTo("PERMANENT");
        assertThat(a.path("attempts").asInt()).isEqualTo(1);
        assertThat(a.path("error").asText()).contains("Memory scope TENANT is not readable");
        JsonNode calls = alice.get("/api/v1/workflow-runs/" + runId + "/tool-calls").body();
        assertThat(calls.get(0).path("status").asText()).isEqualTo("DENIED");
        assertThat(calls.get(0).path("toolId").asText()).isEqualTo("memory.search");
    }

    @Test
    void approvalRequiredToolsNeedAGateAndUnavailableToolsAreDeniedEvenAfterApproval() {
        ApiClient alice = as(ALICE);
        Map<String, Object> runner = base(unique("runner"), "Runner " + unique("x"), "Build engineer");
        runner.put("capabilities", List.of("command.execute"));
        runner.put("tools", List.of("command.run"));
        runner.put("behavior", m("operations", List.of(m("id", "tests", "tool", "command.run", "args", m("command", "unit-tests")))));
        Response created = alice.post("/api/v1/agent-definitions", runner);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String key = created.text("key");
        Response noGate = alice.post("/api/v1/workflows", m("name", "No gate " + unique("g"), "spec", spec(step("a", key, List.of(), m()))));
        assertThat(noGate.status()).isEqualTo(422);
        assertThat(noGate.body().path("errors").toString()).contains("requires an approval gate");

        String wf = createWorkflow(alice, unique("gated"), spec(with(step("a", key, List.of(), m()), "approval", m("required", true))));
        String runId = start(alice, wf, m());
        JsonNode waiting = awaitStatus(alice, runId, Set.of("WAITING_APPROVAL"), WAIT);
        alice.post("/api/v1/workflow-runs/" + runId + "/steps/" + step(waiting, "a").path("id").asText() + "/approve", m());
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(step(run, "a").path("error").asText()).contains("not available here");
    }

    @Test
    void secretsAreNeverStoredAsArtifactsAndAreRedactedFromToolLogs() {
        ApiClient alice = as(ALICE);
        String echoKey = createAgent(alice, echoAgent(unique("echo")));
        String wf = createWorkflow(alice, unique("secret"), spec(step("a", echoKey, List.of(),
                m("label", "leak", "value", "api_key = sk-abcdefghijklmnopqrstuvwxyz0123456789"))));
        String runId = start(alice, wf, m());
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(step(run, "a").path("error").asText()).contains("looks like it contains a credential");
        assertThat(alice.get("/api/v1/workflow-runs/" + runId + "/artifacts").body()).isEmpty();
        String calls = alice.get("/api/v1/workflow-runs/" + runId + "/tool-calls").body().toString();
        assertThat(calls).contains("[redacted").doesNotContain("sk-abcdefghijklmnopqrstuvwxyz0123456789");
        // The persisted step input is redacted too (the executor received the real value, nothing stored it).
        String stored = step(run, "a").path("input").toString();
        assertThat(stored).contains("[redacted").doesNotContain("sk-abcdefghijklmnopqrstuvwxyz0123456789");
    }

    @Test
    void runStateIsReadableByTheTenantOnlyAndHandoffsStayWithTheirAgents() {
        ApiClient alice = as(ALICE);
        String echoKey = createAgent(alice, echoAgent(unique("echo")));
        String runId = start(alice, createWorkflow(alice, unique("handoff"), spec(step("a", echoKey, List.of(), m("label", "a")))), m());
        JsonNode run = await(alice, runId, r -> !step(r, "a").path("handoffId").asText().isEmpty()
                && "SUCCEEDED".equals(r.path("status").asText()), WAIT);
        String handoffId = step(run, "a").path("handoffId").asText();
        String status = "";
        for (int i = 0; i < 50 && !status.equals("COMPLETED"); i++) {
            status = alice.get("/api/v1/handoffs/" + handoffId).body().path("status").asText();
            com.memora.support.Workflows.sleep(100);
        }
        assertThat(status).isEqualTo("COMPLETED");
        assertThat(as(BOB).get("/api/v1/handoffs/" + handoffId).status()).isEqualTo(404);
    }
}
