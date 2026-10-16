package com.memora.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.security.TenantContext;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HandoffIT extends IntegrationTest {

    @Autowired
    HandoffService handoffService;

    private ApiClient alice;
    private ApiClient developerAgent;
    private ApiClient testerAgent;
    private String plannerId;
    private String developerId;
    private String testerId;

    @BeforeEach
    void setUp() {
        alice = as(ALICE);
        plannerId = agentId(alice, "Planner");
        developerId = agentId(alice, "Developer");
        testerId = agentId(alice, "Tester");
        developerAgent = anonymous().withAgentKey(alice.post("/api/v1/agents/" + developerId + "/rotate-key", null).text("apiKey"));
        testerAgent = anonymous().withAgentKey(alice.post("/api/v1/agents/" + testerId + "/rotate-key", null).text("apiKey"));
    }

    private JsonNode newTask() {
        return alice.post("/api/v1/tasks", Map.of("title", "Handoff test " + UUID.randomUUID())).body();
    }

    private Response handoff(String taskId, String to, List<String> memoryRefs, Integer maxAttempts) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("taskId", taskId, "fromAgentId", plannerId, "toAgentId", to,
                "summary", "Please implement", "nextAction", "Implement and hand to tester",
                "decisions", List.of("Use PostgreSQL"), "artifacts", Map.of("spec", "v1"), "memoryRefs", memoryRefs));
        if (maxAttempts != null) {
            body.put("maxAttempts", maxAttempts);
        }
        return alice.post("/api/v1/handoffs", body);
    }

    @Test
    void handoffCarriesStructuredPayloadAndReassignsTheTask() {
        JsonNode task = newTask();
        String memory = alice.post("/api/v1/memories", Map.of("type", "SEMANTIC", "content", "Spec: use PostgreSQL"))
                .body().path("memory").path("id").asText();
        Response h = handoff(task.path("id").asText(), developerId, List.of(memory), null);
        assertThat(h.status()).isEqualTo(201);
        JsonNode body = h.body();
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("memoryRefs").get(0).asText()).isEqualTo(memory);
        assertThat(body.path("decisions").get(0).asText()).isEqualTo("Use PostgreSQL");
        assertThat(body.path("artifacts").path("spec").asText()).isEqualTo("v1");
        assertThat(body.path("taskVersion").asLong()).isEqualTo(1);
        assertThat(alice.get("/api/v1/tasks/" + task.path("id").asText()).body().path("assigneeAgentId").asText()).isEqualTo(developerId);

        Response inbox = developerAgent.get("/api/v1/agents/" + developerId + "/inbox");
        assertThat(inbox.status()).isEqualTo(200);
        assertThat(inbox.body().findValuesAsText("id")).contains(body.path("id").asText());
    }

    @Test
    void receiverAcknowledgesIdempotentlyAndCompletes() {
        String id = handoff(newTask().path("id").asText(), developerId, List.of(), null).text("id");
        Response ack1 = developerAgent.post("/api/v1/handoffs/" + id + "/ack", null);
        Response ack2 = developerAgent.post("/api/v1/handoffs/" + id + "/ack", null);
        assertThat(ack1.status()).isEqualTo(200);
        assertThat(ack1.text("status")).isEqualTo("ACKNOWLEDGED");
        assertThat(ack2.status()).isEqualTo(200);
        assertThat(ack2.body().path("attempts").asInt()).isEqualTo(1);
        assertThat(ack1.text("leaseExpiresAt")).isNotBlank();
        Response done = developerAgent.post("/api/v1/handoffs/" + id + "/complete", Map.of("result", Map.of("ok", true)));
        assertThat(done.text("status")).isEqualTo("COMPLETED");
        assertThat(done.body().path("result").path("ok").asBoolean()).isTrue();
    }

    @Test
    void onlyTheReceiverCanActOnAHandoff() {
        String id = handoff(newTask().path("id").asText(), developerId, List.of(), null).text("id");
        Response stranger = testerAgent.post("/api/v1/handoffs/" + id + "/ack", null);
        assertThat(stranger.status()).isEqualTo(404);
        Response otherInbox = testerAgent.get("/api/v1/agents/" + developerId + "/inbox");
        assertThat(otherInbox.status()).isEqualTo(403);
    }

    @Test
    void failedHandoffCanBeRetriedAndEventuallyDeadLetters() {
        String id = handoff(newTask().path("id").asText(), developerId, List.of(), 2).text("id");
        developerAgent.post("/api/v1/handoffs/" + id + "/ack", null);
        Response failed = developerAgent.post("/api/v1/handoffs/" + id + "/fail", Map.of("reason", "flaky environment"));
        assertThat(failed.text("status")).isEqualTo("FAILED");
        assertThat(failed.text("failureReason")).isEqualTo("flaky environment");
        assertThat(developerAgent.post("/api/v1/handoffs/" + id + "/complete", null).status()).isEqualTo(409);

        Response retried = developerAgent.post("/api/v1/handoffs/" + id + "/retry", null);
        assertThat(retried.text("status")).isEqualTo("PENDING");
        Response ack = developerAgent.post("/api/v1/handoffs/" + id + "/ack", null);
        assertThat(ack.body().path("attempts").asInt()).isEqualTo(2);
        Response failedAgain = developerAgent.post("/api/v1/handoffs/" + id + "/fail", Map.of("reason", "still broken"));
        assertThat(failedAgain.text("status")).isEqualTo("DEAD_LETTER");
        assertThat(developerAgent.post("/api/v1/handoffs/" + id + "/retry", null).status()).isEqualTo(409);
    }

    @Test
    void expiredLeaseReturnsTheHandoffToTheInboxSoAnotherAttemptCanFinishIt() {
        String id = handoff(newTask().path("id").asText(), developerId, List.of(), null).text("id");
        developerAgent.post("/api/v1/handoffs/" + id + "/ack", null);
        // The claiming agent "crashes": it never completes. Run recovery as if the lease had expired.
        List<HandoffDtos.HandoffView> recovered = TenantContext.callAsSystem(
                () -> handoffService.recoverExpiredLeases(Instant.now().plusSeconds(3600), 100));
        assertThat(recovered).extracting(h -> h.id().toString()).contains(id);
        JsonNode h = alice.get("/api/v1/handoffs/" + id).body();
        assertThat(h.path("status").asText()).isEqualTo("PENDING");
        assertThat(h.path("failureReason").asText()).contains("Lease expired");
        Response ack = developerAgent.post("/api/v1/handoffs/" + id + "/ack", null);
        assertThat(ack.body().path("attempts").asInt()).isEqualTo(2);
        assertThat(developerAgent.post("/api/v1/handoffs/" + id + "/complete", null).text("status")).isEqualTo("COMPLETED");
        JsonNode events = alice.get("/api/v1/tasks/" + h.path("taskId").asText() + "/events").body();
        assertThat(events.findValuesAsText("eventType")).contains("handoff_lease_expired", "handoff_completed");
    }

    @Test
    void aTaskHasAtMostOneActiveHandoff() {
        String taskId = newTask().path("id").asText();
        assertThat(handoff(taskId, developerId, List.of(), null).status()).isEqualTo(201);
        Response second = handoff(taskId, testerId, List.of(), null);
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.text("code")).isEqualTo("active_handoff_exists");
    }

    @Test
    void staleTaskVersionBlocksTheHandoff() {
        JsonNode task = newTask();
        Response r = alice.post("/api/v1/handoffs", Map.of("taskId", task.path("id").asText(), "fromAgentId", plannerId,
                "toAgentId", developerId, "summary", "s", "nextAction", "n", "expectedTaskVersion", 7));
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.text("code")).isEqualTo("stale_version");
    }

    @Test
    void memoryReferencesMustExistInTheTenant() {
        Response r = handoff(newTask().path("id").asText(), developerId, List.of(UUID.randomUUID().toString()), null);
        assertThat(r.status()).isEqualTo(404);
    }

    @Test
    void agentsSendHandoffsOnlyAsThemselves() {
        String taskId = newTask().path("id").asText();
        Response spoof = developerAgent.post("/api/v1/handoffs", Map.of("taskId", taskId, "fromAgentId", plannerId,
                "toAgentId", testerId, "summary", "s", "nextAction", "n"));
        assertThat(spoof.status()).isEqualTo(403);
        Response own = developerAgent.post("/api/v1/handoffs", Map.of("taskId", taskId, "toAgentId", testerId,
                "summary", "s", "nextAction", "n"));
        assertThat(own.status()).isEqualTo(201);
        assertThat(own.text("fromAgentId")).isEqualTo(developerId);
    }

    @Test
    void retriedCreateWithSameKeyDoesNotDuplicate() {
        String taskId = newTask().path("id").asText();
        String key = uniqueKey();
        Map<String, Object> body = Map.of("taskId", taskId, "fromAgentId", plannerId, "toAgentId", developerId,
                "summary", "s", "nextAction", "n");
        Response a = alice.post("/api/v1/handoffs", body, key);
        Response b = alice.post("/api/v1/handoffs", body, key);
        assertThat(a.status()).isEqualTo(201);
        assertThat(b.text("id")).isEqualTo(a.text("id"));
        assertThat(alice.get("/api/v1/handoffs?taskId=" + taskId).body()).hasSize(1);
    }
}
