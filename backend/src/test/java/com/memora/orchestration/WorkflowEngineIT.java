package com.memora.orchestration;

import static com.memora.orchestration.WorkflowFixtures.createAgent;
import static com.memora.orchestration.WorkflowFixtures.createWorkflow;
import static com.memora.orchestration.WorkflowFixtures.echoAgent;
import static com.memora.orchestration.WorkflowFixtures.m;
import static com.memora.orchestration.WorkflowFixtures.spec;
import static com.memora.orchestration.WorkflowFixtures.step;
import static com.memora.orchestration.WorkflowFixtures.strictAgent;
import static com.memora.orchestration.WorkflowFixtures.unique;
import static com.memora.orchestration.WorkflowFixtures.with;
import static com.memora.support.Workflows.await;
import static com.memora.support.Workflows.awaitStatus;
import static com.memora.support.Workflows.iterations;
import static com.memora.support.Workflows.start;
import static com.memora.support.Workflows.step;
import static com.memora.support.Workflows.summary;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** User-defined agents and workflows run through the generic engine: ordering, parallelism, conditions, retries, control. */
class WorkflowEngineIT extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Test
    void independentStepsRunInParallelDependentsWaitAndConditionsRoute() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        String wf = createWorkflow(alice, unique("routing"), spec(
                step("a", echo, List.of(), m("label", "a", "value", "one")),
                step("b", echo, List.of(), m("label", "b", "value", "two")),
                step("c", echo, List.of("a", "b"), m("label", "c", "value", "${steps.a.output.value}+${steps.b.output.value}",
                        "flag", "${run.inputs.mode}")),
                with(step("d", echo, List.of("c"), m("label", "d")), "when", m("path", "steps.c.output.flag", "op", "eq", "value", "go")),
                with(step("e", echo, List.of("c"), m("label", "e")), "when", m("path", "steps.c.output.flag", "op", "eq", "value", "other")),
                step("f", echo, List.of("d", "e"), m("label", "f", "value", "${steps.d.status}/${steps.e.status}"))));
        String runId = start(alice, wf, m("inputs", m("mode", "go"), "options", m("pacingMs", 400)));
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(run.path("status").asText()).as(summary(run)).isEqualTo("SUCCEEDED");

        JsonNode a = step(run, "a");
        JsonNode b = step(run, "b");
        JsonNode c = step(run, "c");
        assertThat(Instant.parse(a.path("startedAt").asText())).isBefore(Instant.parse(b.path("finishedAt").asText()));
        assertThat(Instant.parse(b.path("startedAt").asText())).isBefore(Instant.parse(a.path("finishedAt").asText()));
        assertThat(Instant.parse(c.path("startedAt").asText())).isAfterOrEqualTo(Instant.parse(a.path("finishedAt").asText()))
                .isAfterOrEqualTo(Instant.parse(b.path("finishedAt").asText()));
        assertThat(c.path("input").path("value").asText()).isEqualTo("one+two");
        assertThat(c.path("readyReason").path("dependencies")).hasSize(2);

        assertThat(step(run, "d").path("status").asText()).isEqualTo("SUCCEEDED");
        JsonNode e = step(run, "e");
        assertThat(e.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(e.path("readyReason").path("condition").path("result").asBoolean()).isFalse();
        assertThat(e.path("readyReason").path("condition").path("trace").toString()).contains("actual \\\"go\\\"");
        assertThat(step(run, "f").path("output").path("value").asText()).isEqualTo("SUCCEEDED/SKIPPED");
        assertThat(run.path("layers").toString()).isEqualTo("[[\"a\",\"b\"],[\"c\"],[\"d\",\"e\"],[\"f\"]]");
    }

    @Test
    void transientFailuresAreRetriedWithinTheirBoundAndPermanentFailuresAreNot() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        String strict = createAgent(alice, strictAgent(unique("strict")));
        String wf = createWorkflow(alice, unique("retries"), spec(
                with(step("a", echo, List.of(), m("label", "a", "value", "x")), "retry", m("maxAttempts", 3, "backoffSeconds", 0)),
                step("b", strict, List.of("a"), m("label", "b", "value", "${run.inputs.mode}"))));

        // One injected transient failure: the second attempt succeeds.
        String r1 = start(alice, wf, m("inputs", m("mode", "fine"), "options", m("faults", m("a", m("failAttempts", 1)))));
        JsonNode run1 = awaitStatus(alice, r1, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(run1.path("status").asText()).as(summary(run1)).isEqualTo("SUCCEEDED");
        assertThat(step(run1, "a").path("attempts").asInt()).isEqualTo(2);
        JsonNode events = alice.get("/api/v1/workflow-runs/" + r1 + "/events").body();
        assertThat(events.findValuesAsText("type")).contains("retry_scheduled", "step_succeeded");
        assertThat(events.toString()).contains("Injected fault (demo)");

        // Rule violation: permanent, attempted once, downstream reasons recorded, run FAILED.
        String r2 = start(alice, wf, m("inputs", m("mode", "bad")));
        JsonNode run2 = awaitStatus(alice, r2, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(run2.path("status").asText()).isEqualTo("FAILED");
        JsonNode b = step(run2, "b");
        assertThat(b.path("attempts").asInt()).isEqualTo(1);
        assertThat(b.path("errorKind").asText()).isEqualTo("PERMANENT");
        assertThat(b.path("error").asText()).contains("violates the rule");
        assertThat(run2.path("completion").path("passed").asBoolean()).isFalse();
    }

    @Test
    void exhaustedRetriesBlockDownstreamUntilAManualRetrySucceeds() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        String wf = createWorkflow(alice, unique("exhaust"), spec(
                with(step("a", echo, List.of(), m("label", "a")), "retry", m("maxAttempts", 2, "backoffSeconds", 0)),
                step("b", echo, List.of("a"), m("label", "b")),
                step("c", echo, List.of("b"), m("label", "c"))));
        String runId = start(alice, wf, m("options", m("faults", m("a", m("failAttempts", 2)))));
        JsonNode failed = awaitStatus(alice, runId, Set.of("FAILED", "SUCCEEDED"), WAIT);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        JsonNode a = step(failed, "a");
        assertThat(a.path("attempts").asInt()).isEqualTo(2);
        assertThat(a.path("errorKind").asText()).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(a.path("canRetry").asBoolean()).isTrue();
        assertThat(step(failed, "b").path("status").asText()).isEqualTo("BLOCKED");
        assertThat(step(failed, "c").path("status").asText()).isEqualTo("BLOCKED");

        Response retry = alice.post("/api/v1/workflow-runs/" + runId + "/steps/" + a.path("id").asText() + "/retry", null);
        assertThat(retry.status()).as(retry.toString()).isEqualTo(200);
        JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(done.path("status").asText()).as(summary(done)).isEqualTo("SUCCEEDED");
        assertThat(step(done, "a").path("attempts").asInt()).isEqualTo(3);
        assertThat(step(done, "c").path("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void completionCriteriaDecideSuccessNotStepStatuses() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        Map<String, Object> spec = spec(step("a", echo, List.of(), m("label", "a", "value", "actual")));
        spec.put("completion", m("requiredSteps", List.of("a"), "criteria", List.of(
                m("name", "Value is what we wanted", "condition", m("path", "steps.a.output.value", "op", "eq", "value", "expected")))));
        String runId = start(alice, createWorkflow(alice, unique("criteria"), spec), m());
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(step(run, "a").path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        JsonNode criterion = run.path("completion").path("criteria").get(0);
        assertThat(criterion.path("passed").asBoolean()).isFalse();
        assertThat(criterion.path("trace").toString()).contains("actual");
        assertThat(run.path("completion").path("summary").asText()).contains("1 of 1 completion criteria not met");
    }

    @Test
    void pausedRunsStartNothingNewAndResumeWithoutRerunningCompletedSteps() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        String wf = createWorkflow(alice, unique("pause"), spec(
                step("a", echo, List.of(), m("label", "a")),
                step("b", echo, List.of("a"), m("label", "b")),
                step("c", echo, List.of("b"), m("label", "c"))));
        String runId = start(alice, wf, m("options", m("pacingMs", 1500)));
        await(alice, runId, r -> step(r, "a").path("status").asText().equals("RUNNING"), WAIT);
        assertThat(alice.post("/api/v1/workflow-runs/" + runId + "/pause", null).body().path("status").asText()).isEqualTo("PAUSED");
        // a finishes and is recorded; b becomes READY but is not started while paused.
        JsonNode paused = await(alice, runId, r -> step(r, "a").path("status").asText().equals("SUCCEEDED"), WAIT);
        com.memora.support.Workflows.sleep(1500);
        paused = com.memora.support.Workflows.run(alice, runId);
        assertThat(paused.path("status").asText()).isEqualTo("PAUSED");
        assertThat(step(paused, "b").path("status").asText()).isEqualTo("READY");
        assertThat(step(paused, "b").path("attempts").asInt()).isZero();

        assertThat(alice.post("/api/v1/workflow-runs/" + runId + "/resume", null).status()).isEqualTo(200);
        JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(step(done, "a").path("attempts").asInt()).isEqualTo(1);
        JsonNode events = alice.get("/api/v1/workflow-runs/" + runId + "/events").body();
        assertThat(events.findValuesAsText("type")).contains("run_paused", "run_resumed");
    }

    @Test
    void cancellingStopsPendingWorkAndForbidsRetries() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        String wf = createWorkflow(alice, unique("cancel"), spec(
                step("a", echo, List.of(), m("label", "a")),
                step("b", echo, List.of("a"), m("label", "b"))));
        String runId = start(alice, wf, m("options", m("pacingMs", 1500)));
        await(alice, runId, r -> step(r, "a").path("status").asText().equals("RUNNING"), WAIT);
        JsonNode cancelled = alice.post("/api/v1/workflow-runs/" + runId + "/cancel", null).body();
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(step(cancelled, "b").path("status").asText()).isEqualTo("CANCELLED");
        JsonNode settled = await(alice, runId, r -> step(r, "a").path("status").asText().equals("CANCELLED"), WAIT);
        assertThat(settled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(alice.post("/api/v1/workflow-runs/" + runId + "/resume", null).status()).isEqualTo(409);
        assertThat(alice.post("/api/v1/workflow-runs/" + runId + "/cancel", null).status()).isEqualTo(409);
    }

    @Test
    void loopsAreBoundedAndFailWhenTheirExitConditionNeverHolds() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        String wf = createWorkflow(alice, unique("loop"), spec(
                with(step("check", echo, List.of(), m("label", "check", "passed", false)),
                        "loop", m("until", m("path", "output.passed", "op", "eq", "value", true), "maxIterations", 2,
                                "remediationStep", "fix")),
                with(step("fix", echo, List.of(), m("label", "fix", "value", "${steps.check.iteration}")), "remediation", true)));
        String runId = start(alice, wf, m());
        JsonNode run = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(iterations(run, "check")).hasSize(2);
        assertThat(iterations(run, "fix")).hasSize(1);
        JsonNode last = step(run, "check");
        assertThat(last.path("status").asText()).isEqualTo("FAILED");
        assertThat(last.path("error").asText()).contains("Exit condition still false after 2 iteration(s)");
        assertThat(step(run, "fix").path("dependsOn").get(0).asText()).isEqualTo("check#1");
    }

    @Test
    void invalidGraphsAndReferencesAreRejected() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        Response cycle = alice.post("/api/v1/workflows", m("name", "Cycle " + unique("c"), "spec", spec(
                step("a", echo, List.of("c"), m()), step("b", echo, List.of("a"), m()), step("c", echo, List.of("b"), m()))));
        assertThat(cycle.status()).isEqualTo(422);
        assertThat(cycle.body().path("errors").toString()).contains("dependency cycle among [a, b, c]");

        Response unknownDep = alice.post("/api/v1/workflows", m("name", "Unknown " + unique("u"), "spec", spec(
                step("a", echo, List.of("ghost"), m()))));
        assertThat(unknownDep.body().path("errors").toString()).contains("unknown step 'ghost'");

        Response notUpstream = alice.post("/api/v1/workflows", m("name", "Ref " + unique("r"), "spec", spec(
                step("a", echo, List.of(), m("label", "a")), step("b", echo, List.of(), m("value", "${steps.a.output.value}")))));
        assertThat(notUpstream.status()).isEqualTo(422);
        assertThat(notUpstream.body().path("errors").toString()).contains("not an upstream dependency");

        Response unknownAgent = alice.post("/api/v1/workflows", m("name", "Agent " + unique("a"), "spec", spec(step("a", "nobody", List.of(), m()))));
        assertThat(unknownAgent.body().path("errors").toString()).contains("no agent definition with key 'nobody'");

        Response unboundedLoop = alice.post("/api/v1/workflows", m("name", "Loop " + unique("l"), "spec", spec(
                with(step("a", echo, List.of(), m()), "loop", m("until", m("path", "output.passed", "op", "eq", "value", true),
                        "maxIterations", 50, "remediationStep", "a")))));
        assertThat(unboundedLoop.body().path("errors").toString()).contains("between 1 and 5").contains("must set remediation: true");

        JsonNode layers = alice.post("/api/v1/workflows/validate", m("spec", spec(
                step("a", echo, List.of(), m()), step("b", echo, List.of(), m()), step("c", echo, List.of("a", "b"), m())))).body();
        assertThat(layers.path("valid").asBoolean()).isTrue();
        assertThat(layers.path("analysis").path("parallelGroups").toString()).isEqualTo("[[\"a\",\"b\"]]");

        // Unknown inputs and missing required inputs are refused at start.
        Map<String, Object> withInput = spec(step("a", echo, List.of(), m("label", "a")));
        withInput.put("inputs", List.of(m("name", "goal", "type", "text", "required", true)));
        String wf = createWorkflow(alice, unique("inputs"), withInput);
        Response missing = alice.post("/api/v1/workflows/" + wf + "/runs", m());
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.text("detail")).contains("input 'goal' is required");
        Response extra = alice.post("/api/v1/workflows/" + wf + "/runs", m("inputs", m("goal", "x", "surprise", 1)));
        assertThat(extra.text("detail")).contains("'surprise' is not declared");
    }

    @Test
    void anExternalAgentCompletesItsStepThroughTheExistingHandoffApi() {
        ApiClient alice = as(ALICE);
        String echo = createAgent(alice, echoAgent(unique("echo")));
        Map<String, Object> external = WorkflowFixtures.base(unique("ext"), "External " + unique("x"), "Developer (external)");
        external.put("executor", "external");
        external.put("outputSchema", m("type", "object", "required", List.of("summary")));
        Response created = alice.post("/api/v1/agent-definitions", external);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        String externalKey = created.text("key");
        String principal = created.text("agentId");
        String apiKey = alice.post("/api/v1/agents/" + principal + "/rotate-key", null).text("apiKey");
        ApiClient agent = anonymous().withAgentKey(apiKey);

        String wf = createWorkflow(alice, unique("external"), spec(
                step("prepare", echo, List.of(), m("label", "prepare", "value", "brief")),
                step("work", externalKey, List.of("prepare"), m("brief", "${steps.prepare.output.value}")),
                step("after", echo, List.of("work"), m("label", "after", "value", "${steps.work.output.summary}"))));
        String runId = start(alice, wf, m());
        JsonNode waiting = await(alice, runId, r -> step(r, "work").path("status").asText().equals("RUNNING")
                && !step(r, "work").path("handoffId").asText().isEmpty(), WAIT);
        String handoffId = step(waiting, "work").path("handoffId").asText();

        JsonNode inbox = agent.get("/api/v1/agents/" + principal + "/inbox").body();
        assertThat(inbox.findValuesAsText("id")).contains(handoffId);
        assertThat(agent.post("/api/v1/handoffs/" + handoffId + "/ack", null).status()).isEqualTo(200);
        Response complete = agent.post("/api/v1/handoffs/" + handoffId + "/complete",
                m("result", m("output", m("summary", "implemented outside Memora"))));
        assertThat(complete.status()).as(complete.toString()).isEqualTo(200);

        JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), WAIT);
        assertThat(done.path("status").asText()).as(summary(done)).isEqualTo("SUCCEEDED");
        assertThat(step(done, "after").path("output").path("value").asText()).isEqualTo("implemented outside Memora");
        // Someone else's key cannot report this step.
        Response foreign = as(AARON).post("/api/v1/workflow-runs/" + runId + "/steps/" + step(done, "work").path("id").asText() + "/complete",
                m("output", m("summary", "x")));
        assertThat(foreign.status()).isEqualTo(403);
    }
}
