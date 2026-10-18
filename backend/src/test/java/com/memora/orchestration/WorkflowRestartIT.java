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
import com.memora.MemoraApplication;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * A workflow survives a backend restart: work completed before the restart is kept and not repeated, and a step
 * that was in flight when the old process died is detected at startup and retried by the new process.
 */
class WorkflowRestartIT {

    private static ConfigurableApplicationContext boot(boolean recoverOnStartup) {
        return new SpringApplicationBuilder(MemoraApplication.class)
                .profiles("test")
                .run("--server.port=0",
                        "--memora.test.clean-db=false",
                        "--memora.workflow.recover-on-startup=" + recoverOnStartup,
                        "--spring.datasource.url=" + TestDatabase.URL,
                        "--spring.datasource.username=" + TestDatabase.USER,
                        "--spring.datasource.password=" + TestDatabase.PASSWORD);
    }

    private static ApiClient login(ConfigurableApplicationContext ctx) {
        int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
        Response r = ApiClient.anonymous(port).post("/api/v1/auth/login", m("email", "alice@acme.test", "password", "memora-demo"));
        assertThat(r.status()).isEqualTo(200);
        return ApiClient.anonymous(port).withBearer(r.text("token"));
    }

    @Test
    void completedStepsAreKeptAndAnInterruptedStepIsRecoveredAfterARestart() throws Exception {
        String runId;
        String cStepId;
        try (ConfigurableApplicationContext first = boot(false)) {
            ApiClient alice = login(first);
            String echo = createAgent(alice, echoAgent(unique("echo")));
            String wf = createWorkflow(alice, unique("restart"), spec(
                    step("a", echo, List.of(), m("label", "a", "value", "first")),
                    step("b", echo, List.of("a"), m("label", "b", "value", "${steps.a.output.value}-second")),
                    step("c", echo, List.of("b"), m("label", "c", "value", "${steps.b.output.value}-third"))));
            runId = start(alice, wf, m("options", m("pacingMs", 1500)));
            await(alice, runId, r -> step(r, "b").path("status").asText().equals("RUNNING"), Duration.ofSeconds(60));
            alice.post("/api/v1/workflow-runs/" + runId + "/pause", null);
            // Graceful shutdown lets b finish and record its result; c stays READY because the run is paused.
            JsonNode beforeStop = await(alice, runId, r -> step(r, "b").path("status").asText().equals("SUCCEEDED"),
                    Duration.ofSeconds(30));
            cStepId = step(beforeStop, "c").path("id").asText();
        }

        // Simulate a crash in the middle of c: the dead process had claimed it and never reported back.
        try (Connection c = DriverManager.getConnection(TestDatabase.URL, TestDatabase.USER, TestDatabase.PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("SELECT set_config('app.rls_bypass', 'on', false)");
            try (PreparedStatement ps = c.prepareStatement("UPDATE workflow_step_runs SET status = 'RUNNING', attempts = 1, "
                    + "lease_owner = 'node-crashed', lease_expires_at = now() + interval '1 hour', started_at = now() WHERE id = ?::uuid")) {
                ps.setString(1, cStepId);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }

        try (ConfigurableApplicationContext second = boot(true)) {
            ApiClient alice = login(second);
            JsonNode recovered = com.memora.support.Workflows.run(alice, runId);
            assertThat(step(recovered, "c").path("status").asText()).isEqualTo("READY");
            assertThat(alice.post("/api/v1/workflow-runs/" + runId + "/resume", null).status()).isEqualTo(200);
            JsonNode done = awaitStatus(alice, runId, Set.of("SUCCEEDED", "FAILED"), Duration.ofSeconds(60));
            assertThat(done.path("status").asText()).as(summary(done)).isEqualTo("SUCCEEDED");
            assertThat(step(done, "a").path("attempts").asInt()).isEqualTo(1);
            assertThat(step(done, "b").path("attempts").asInt()).isEqualTo(1);
            assertThat(step(done, "c").path("attempts").asInt()).isEqualTo(2);
            assertThat(step(done, "c").path("output").path("value").asText()).isEqualTo("first-second-third");
            String events = alice.get("/api/v1/workflow-runs/" + runId + "/events").body().toString();
            assertThat(events).contains("The backend restarted while this step was running (worker node-crashed)");
        }
    }
}
