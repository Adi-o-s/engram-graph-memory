package com.memora.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Helpers for workflow tests: find definitions, start runs and wait for persisted run state. */
public final class Workflows {

    private Workflows() {
    }

    public static String workflowId(ApiClient client, String key) {
        for (JsonNode w : client.get("/api/v1/workflows").body()) {
            if (w.path("key").asText().equals(key)) {
                return w.path("id").asText();
            }
        }
        throw new IllegalStateException("No workflow " + key);
    }

    public static String agentDefinitionId(ApiClient client, String key) {
        for (JsonNode a : client.get("/api/v1/agent-definitions").body()) {
            if (a.path("key").asText().equals(key)) {
                return a.path("id").asText();
            }
        }
        throw new IllegalStateException("No agent definition " + key);
    }

    public static String start(ApiClient client, String workflowId, Map<String, Object> body) {
        Response r = client.post("/api/v1/workflows/" + workflowId + "/runs", body);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.text("runId");
    }

    public static JsonNode run(ApiClient client, String runId) {
        Response r = client.get("/api/v1/workflow-runs/" + runId);
        assertThat(r.status()).as(r.toString()).isEqualTo(200);
        return r.body();
    }

    /** Polls the run until the predicate holds (or fails with the last state and its events). */
    public static JsonNode await(ApiClient client, String runId, Predicate<JsonNode> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            last = run(client, runId);
            if (condition.test(last)) {
                return last;
            }
            sleep(100);
        }
        throw new AssertionError("Run " + runId + " did not reach the expected state within " + timeout + ". Last state: "
                + (last == null ? null : last.path("status")) + " steps: " + summary(last) + "\nEvents: "
                + client.get("/api/v1/workflow-runs/" + runId + "/events").body());
    }

    public static JsonNode awaitStatus(ApiClient client, String runId, Set<String> statuses, Duration timeout) {
        return await(client, runId, r -> statuses.contains(r.path("status").asText()), timeout);
    }

    public static JsonNode step(JsonNode run, String key) {
        JsonNode latest = null;
        for (JsonNode s : run.path("steps")) {
            if (s.path("key").asText().equals(key) && (latest == null || s.path("iteration").asInt() > latest.path("iteration").asInt())) {
                latest = s;
            }
        }
        if (latest == null) {
            throw new AssertionError("No step " + key + " in " + summary(run));
        }
        return latest;
    }

    public static List<JsonNode> iterations(JsonNode run, String key) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode s : run.path("steps")) {
            if (s.path("key").asText().equals(key)) {
                out.add(s);
            }
        }
        return out;
    }

    public static String summary(JsonNode run) {
        if (run == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode s : run.path("steps")) {
            sb.append(s.path("key").asText()).append('#').append(s.path("iteration").asInt()).append('=')
                    .append(s.path("status").asText());
            if (!s.path("error").isMissingNode() && !s.path("error").isNull()) {
                sb.append("(").append(s.path("error").asText()).append(")");
            }
            sb.append(' ');
        }
        return sb.toString();
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
