package com.memora.orchestration;

import static com.memora.orchestration.WorkflowFixtures.base;
import static com.memora.orchestration.WorkflowFixtures.createAgent;
import static com.memora.orchestration.WorkflowFixtures.createWorkflow;
import static com.memora.orchestration.WorkflowFixtures.m;
import static com.memora.orchestration.WorkflowFixtures.spec;
import static com.memora.orchestration.WorkflowFixtures.step;
import static com.memora.orchestration.WorkflowFixtures.unique;
import static com.memora.support.Workflows.awaitStatus;
import static com.memora.support.Workflows.start;
import static com.memora.support.Workflows.step;
import static com.memora.support.Workflows.summary;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.IntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Agents share memory across steps and runs, only within their policy, with provenance and supersession intact. */
class WorkflowMemoryIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    private static Map<String, Object> recorder(String key) {
        Map<String, Object> a = base(key, "Recorder " + key, "Note taker");
        a.put("capabilities", List.of("memory.write"));
        a.put("tools", List.of("memory.write"));
        a.put("behavior", m("operations", List.of(
                m("id", "note", "tool", "memory.write", "args", m("content", "Working note for ${input.label}", "scope", "RUN",
                        "category", "note", "tags", List.of("note"))),
                m("id", "decision", "tool", "memory.write", "args", m("content", "Decision ${input.label} was chosen", "scope", "PROJECT",
                        "category", "decision", "tags", List.of("decision"))))));
        return a;
    }

    private static Map<String, Object> reader(String key, List<String> scopes) {
        Map<String, Object> a = base(key, "Reader " + key, "Reviewer");
        a.put("capabilities", List.of("memory.read"));
        a.put("tools", List.of("memory.search"));
        a.put("memoryPolicy", m("read", scopes, "write", List.of(), "maxItems", 50));
        a.put("behavior", m("operations", List.of(m("id", "s", "tool", "memory.search", "args", m("limit", 50))),
                "output", m("contents", "${ops.s.items}", "count", "${ops.s.count}")));
        return a;
    }

    @Test
    void laterRunsSeeSharedDecisionsButNeverAnotherRunsWorkingMemory() {
        ApiClient alice = as(ALICE);
        String project = newProject(alice, "mem");
        String rec = createAgent(alice, recorder(unique("rec")));
        String read = createAgent(alice, reader(unique("read"), List.of("RUN", "PROJECT")));
        String wf = createWorkflow(alice, unique("memory"), spec(
                step("record", rec, List.of(), m("label", "${run.inputs.mode}")),
                step("read", read, List.of("record"), m())));
        JsonNode first = awaitStatus(alice, start(alice, wf, m("projectId", project, "inputs", m("mode", "alpha"))),
                Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(first.path("status").asText()).as(summary(first)).isEqualTo("SUCCEEDED");
        JsonNode second = awaitStatus(alice, start(alice, wf, m("projectId", project, "inputs", m("mode", "beta"))),
                Set.of("SUCCEEDED", "FAILED"), WAIT);
        String seen = step(second, "read").path("output").path("contents").toString();
        assertThat(seen).contains("Decision alpha was chosen")      // project memory from the earlier run
                .contains("Decision beta was chosen")
                .contains("Working note for beta")                  // this run's working memory
                .doesNotContain("Working note for alpha");          // another run's working memory

        // Provenance: the memory knows which run, step and agent wrote it.
        JsonNode note = alice.get("/api/v1/memories?q=beta&category=note&projectId=" + project).body().path("items").get(0);
        assertThat(note.path("scope").asText()).isEqualTo("RUN");
        assertThat(note.path("workflowRunId").asText()).isEqualTo(second.path("id").asText());
        assertThat(note.path("metadata").path("stepKey").asText()).isEqualTo("record");
        assertThat(note.path("source").path("createdByAgentId").asText()).isNotBlank();
    }

    @Test
    void theArchitectUsesTheProjectsCurrentFactAndFollowsSupersession() {
        ApiClient alice = as(ALICE);
        String project = newProject(alice, "facts");
        String conversation = newConversation(alice, project);
        alice.post("/api/v1/conversations/" + conversation + "/messages", m("content", "We use MySQL."));
        alice.post("/api/v1/conversations/" + conversation + "/messages", m("content", "We switched from MySQL to PostgreSQL."));
        String wf = createWorkflow(alice, unique("arch"), spec(
                step("requirements", "requirements-analyst", List.of(), m("goal", "${run.inputs.mode}")),
                step("architecture", "solution-architect", List.of("requirements"),
                        m("requirements", "${steps.requirements.output.requirements}"))));
        String goal = "Build an inventory web app with product search and user authentication.";
        JsonNode first = awaitStatus(alice, start(alice, wf, m("projectId", project, "inputs", m("mode", goal))),
                Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(first.path("status").asText()).as(summary(first)).isEqualTo("SUCCEEDED");
        assertThat(step(first, "architecture").path("output").path("database").asText()).isEqualTo("postgresql");

        alice.post("/api/v1/conversations/" + conversation + "/messages", m("content", "We switched from PostgreSQL to SQLite."));
        JsonNode second = awaitStatus(alice, start(alice, wf, m("projectId", project, "inputs", m("mode", goal))),
                Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(step(second, "architecture").path("output").path("database").asText()).isEqualTo("sqlite");

        // The architect's own decision fact was superseded too, with history kept.
        JsonNode decision = alice.get("/api/v1/memories?q=stored&category=decision&projectId=" + project + "&includeShared=false")
                .body().path("items").get(0);
        assertThat(decision.path("value").asText()).isEqualTo("sqlite");
        JsonNode history = alice.get("/api/v1/memories/" + decision.path("id").asText() + "/history").body().path("versions");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).path("status").asText()).isEqualTo("SUPERSEDED");
    }

    @Test
    void userScopeMeansThePersonWhoStartedTheRunAndNobodyElse() {
        ApiClient alice = as(ALICE);
        ApiClient aaron = as(AARON);
        aaron.post("/api/v1/conversations/" + newConversation(aaron, null) + "/messages",
                m("content", "I prefer tabs over spaces."));
        alice.post("/api/v1/conversations/" + newConversation(alice, null) + "/messages",
                m("content", "I prefer dark mode over light mode."));
        String read = createAgent(alice, reader(unique("personal"), List.of("USER")));
        String wf = createWorkflow(alice, unique("personal"), spec(step("read", read, List.of(), m())));
        JsonNode run = awaitStatus(aaron, start(aaron, wf, m()), Set.of("SUCCEEDED", "FAILED"), WAIT);
        String seen = step(run, "read").path("output").path("contents").toString();
        assertThat(seen).contains("tabs").doesNotContain("dark mode");
    }
}
