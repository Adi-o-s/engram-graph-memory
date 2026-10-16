package com.memora.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextIT extends IntegrationTest {

    private ApiClient alice;
    private String project;
    private String otherProject;
    private String conversation;

    @BeforeEach
    void setUp() {
        alice = as(ALICE);
        project = newProject(alice, "ctx");
        otherProject = newProject(alice, "ctx-other");
        conversation = newConversation(alice, project);
        for (int i = 0; i < 12; i++) {
            alice.post("/api/v1/conversations/" + conversation + "/messages", Map.of("content", "Chatter line " + i, "extract", false));
        }
        alice.post("/api/v1/conversations/" + conversation + "/messages",
                Map.of("content", "We use PostgreSQL. When the deploy fails, roll back the database migration."));
        alice.post("/api/v1/memories", Map.of("type", "SEMANTIC", "content", "The billing service in the other project uses Oracle",
                "projectId", otherProject));
        for (int i = 0; i < 10; i++) {
            alice.post("/api/v1/memories", Map.of("type", "EPISODIC", "content", "Database incident number " + i, "projectId", project));
        }
    }

    @Test
    void contextIsScopedToTheProjectAndBounded() {
        Response r = alice.post("/api/v1/context", Map.of("query", "database deploy", "projectId", project,
                "conversationId", conversation, "maxMemories", 6, "maxMessages", 3));
        assertThat(r.status()).isEqualTo(200);
        JsonNode b = r.body();
        int memories = b.path("facts").size() + b.path("procedures").size() + b.path("episodes").size();
        assertThat(memories).isLessThanOrEqualTo(6);
        assertThat(b.path("facts").findValuesAsText("content")).contains("The project uses PostgreSQL as its database.");
        assertThat(b.path("procedures").findValuesAsText("content")).anyMatch(c -> c.contains("roll back the database migration"));
        assertThat(b.path("rendered").asText()).doesNotContain("Oracle");
        assertThat(b.path("messages")).hasSize(3);
        assertThat(b.path("messages").get(2).path("content").asText()).startsWith("We use PostgreSQL");
        assertThat(b.path("stats").path("messagesIncluded").asInt()).isEqualTo(3);
    }

    @Test
    void characterBudgetIsNeverExceeded() {
        Response r = alice.post("/api/v1/context", Map.of("query", "database", "projectId", project,
                "conversationId", conversation, "maxMemories", 30, "maxMessages", 30, "maxChars", 400));
        JsonNode stats = r.body().path("stats");
        assertThat(r.text("rendered").length()).isLessThanOrEqualTo(400);
        assertThat(stats.path("chars").asInt()).isLessThanOrEqualTo(400);
        assertThat(stats.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void retrievalIsCountedAtomically() {
        Response r = alice.post("/api/v1/context", Map.of("query", "PostgreSQL", "projectId", project, "maxMemories", 4));
        String factId = r.body().path("facts").get(0).path("id").asText();
        alice.post("/api/v1/context", Map.of("query", "PostgreSQL", "projectId", project, "maxMemories", 4));
        assertThat(alice.get("/api/v1/memories/" + factId).body().path("accessCount").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThat(alice.post("/api/v1/context", Map.of("maxMemories", 500)).status()).isEqualTo(400);
        assertThat(alice.post("/api/v1/context", Map.of("maxChars", 10)).status()).isEqualTo(400);
    }
}
