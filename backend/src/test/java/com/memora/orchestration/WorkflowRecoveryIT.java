package com.memora.orchestration;

import static com.memora.orchestration.WorkflowFixtures.createAgent;
import static com.memora.orchestration.WorkflowFixtures.createWorkflow;
import static com.memora.orchestration.WorkflowFixtures.echoAgent;
import static com.memora.orchestration.WorkflowFixtures.m;
import static com.memora.orchestration.WorkflowFixtures.spec;
import static com.memora.orchestration.WorkflowFixtures.step;
import static com.memora.orchestration.WorkflowFixtures.unique;
import static com.memora.support.Workflows.await;
import static com.memora.support.Workflows.awaitStatus;
import static com.memora.support.Workflows.start;
import static com.memora.support.Workflows.step;
import static com.memora.support.Workflows.summary;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.orchestration.executor.StepOutcome;
import com.memora.orchestration.run.WorkflowEngine;
import com.memora.orchestration.run.WorkflowEngine.Claimed;
import com.memora.security.TenantContext;
import com.memora.support.ApiClient;
import com.memora.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Claims, fencing and lease recovery, exercised against the real scheduler and database. */
class WorkflowRecoveryIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(90);

    @Autowired
    WorkflowEngine engine;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void concurrentClaimersNeverTakeTheSameStep() throws Exception {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            steps.add(step("s" + i, echo, List.of(), m("label", "s" + i)));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = spec(steps.toArray(new Map[0]));
        String runId = start(alice, createWorkflow(alice, unique("claims"), spec), m("options", m("pacingMs", 1500)));

        // Eight competing claimers race the real scheduler for the same READY steps.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<List<Claimed>>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            String owner = "test-claimer-" + t;
            Callable<List<Claimed>> claim = () -> TenantContext.callAsSystem(() -> engine.claim(owner, 4));
            futures.add(pool.submit(claim));
        }
        List<Claimed> ours = new ArrayList<>();
        Map<UUID, String> owners = new java.util.HashMap<>();
        for (int t = 0; t < futures.size(); t++) {
            for (Claimed c : futures.get(t).get()) {
                ours.add(c);
                owners.put(c.stepRunId(), "test-claimer-" + t);
            }
        }
        pool.shutdown();
        Set<UUID> unique = new HashSet<>();
        ours.forEach(c -> assertThat(unique.add(c.stepRunId())).as("step %s claimed twice", c.stepRunId()).isTrue());

        // Finish our claims like a worker would; the scheduler finishes the rest.
        for (Claimed c : ours) {
            TenantContext.runAsTenant(c.tenantId(), () -> {
                assertThat(engine.prepare(c.tenantId(), c.stepRunId(), owners.get(c.stepRunId()), c.attempt())).isPresent();
                engine.complete(c.tenantId(), c.stepRunId(), owners.get(c.stepRunId()), c.attempt(),
                        StepOutcome.succeeded(Map.of("by", "test"), List.of()));
            });
        }
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(run.path("status").asText()).as(summary(run)).isEqualTo("SUCCEEDED");
        for (JsonNode s : run.path("steps")) {
            assertThat(s.path("attempts").asInt()).as("attempts of %s", s.path("key").asText()).isEqualTo(1);
        }
        long started = 0;
        for (JsonNode e : alice.get("/api/v1/workflow-runs/" + runId + "/events").body()) {
            if (e.path("type").asText().equals("step_started")) {
                started++;
            }
        }
        assertThat(started).isEqualTo(16);
    }

    @Test
    void anExpiredLeaseIsRetriedAndTheOldWorkersLateResultIsIgnored() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        String runId = start(alice, createWorkflow(alice, unique("lease"), spec(step("a", echo, List.of(), m("label", "a", "value", "v")))),
                m("options", m("pacingMs", 3000)));
        JsonNode running = await(alice, runId, r -> step(r, "a").path("status").asText().equals("RUNNING")
                && step(r, "a").path("input").path("label").asText().equals("a"), WAIT);
        String stepId = step(running, "a").path("id").asText();
        UUID tenantId = UUID.fromString(TenantContext.callAsSystem(() -> jdbc.queryForObject(
                "SELECT tenant_id::text FROM workflow_runs WHERE id = ?::uuid", String.class, runId)));

        // A stranger's result for this step is ignored.
        TenantContext.runAsTenant(tenantId, () -> engine.complete(tenantId, UUID.fromString(stepId), "intruder", 1,
                StepOutcome.succeeded(Map.of("forged", true), List.of())));

        // The worker "hangs": its lease runs out while it is still busy.
        TenantContext.runAsSystem(() -> jdbc.update(
                "UPDATE workflow_step_runs SET lease_expires_at = now() - interval '1 second' WHERE id = ?::uuid", stepId));
        assertThat(TenantContext.callAsSystem(() -> engine.recoverExpired("unrelated-owner", false))).isEqualTo(1);

        JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(done.path("status").asText()).as(summary(done)).isEqualTo("SUCCEEDED");
        JsonNode a = step(done, "a");
        assertThat(a.path("attempts").asInt()).isEqualTo(2);
        assertThat(a.path("output").has("forged")).isFalse();
        // The first worker wakes up after the retry was claimed; its late result must be ignored too.
        await(alice, runId, r -> {
            JsonNode events = alice.get("/api/v1/workflow-runs/" + runId + "/events").body();
            return events.findValuesAsText("type").stream().filter("stale_completion_ignored"::equals).count() >= 2;
        }, Duration.ofSeconds(15));
        List<String> types = alice.get("/api/v1/workflow-runs/" + runId + "/events").body().findValuesAsText("type");
        assertThat(types).contains("lease_expired", "retry_scheduled", "step_succeeded");
    }
}
