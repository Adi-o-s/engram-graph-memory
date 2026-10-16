package com.memora.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConversationIT extends IntegrationTest {

    private ApiClient alice;
    private String project;
    private String conversation;

    @BeforeEach
    void setUp() {
        alice = as(ALICE);
        project = newProject(alice, "conv");
        conversation = newConversation(alice, project);
    }

    private Response say(String text) {
        return alice.post("/api/v1/conversations/" + conversation + "/messages", Map.of("content", text));
    }

    @Test
    void messagesArePersistedInOrderWithPagination() {
        for (int i = 1; i <= 5; i++) {
            assertThat(alice.post("/api/v1/conversations/" + conversation + "/messages",
                    Map.of("content", "message " + i, "extract", false)).status()).isEqualTo(201);
        }
        Response page = alice.get("/api/v1/conversations/" + conversation + "/messages?size=2&page=1");
        assertThat(page.body().path("totalItems").asInt()).isEqualTo(5);
        List<Long> seqs = new ArrayList<>();
        page.body().path("items").forEach(m -> seqs.add(m.path("seq").asLong()));
        assertThat(seqs).containsExactly(3L, 4L);
        Response after = alice.get("/api/v1/conversations/" + conversation + "/messages?afterSeq=4");
        assertThat(after.body().path("items")).hasSize(1);
        assertThat(after.body().path("items").get(0).path("content").asText()).isEqualTo("message 5");
        assertThat(alice.get("/api/v1/conversations/" + conversation).body().path("messageCount").asInt()).isEqualTo(5);
    }

    @Test
    void messagesFeedTheExtractorAndMemoriesKeepProvenance() {
        Response r = say("We use PostgreSQL. When an integration test fails, check database migrations first.");
        assertThat(r.status()).isEqualTo(201);
        JsonNode extracted = r.body().path("extracted");
        assertThat(extracted).hasSize(2);
        String messageId = r.body().path("message").path("id").asText();
        for (JsonNode e : extracted) {
            assertThat(e.path("memory").path("source").path("type").asText()).isEqualTo("MESSAGE");
            assertThat(e.path("memory").path("source").path("messageId").asText()).isEqualTo(messageId);
            assertThat(e.path("memory").path("source").path("extractor").asText()).isEqualTo("rule-based-v1");
            assertThat(e.path("memory").path("conversationId").asText()).isEqualTo(conversation);
            assertThat(e.path("memory").path("projectId").asText()).isEqualTo(project);
        }
        Response provenance = alice.get("/api/v1/conversations/" + conversation + "/messages/" + messageId + "/memories");
        assertThat(provenance.body()).hasSize(2);
        // The original message is kept separately and unchanged.
        Response messages = alice.get("/api/v1/conversations/" + conversation + "/messages");
        assertThat(messages.body().path("items").get(0).path("content").asText())
                .isEqualTo("We use PostgreSQL. When an integration test fails, check database migrations first.");
    }

    @Test
    void extractionThroughConversationSupersedesFacts() {
        say("We use MySQL.");
        Response r = say("We switched from MySQL to PostgreSQL.");
        assertThat(r.body().path("extracted").get(0).path("outcome").asText()).isEqualTo("SUPERSEDED_PREVIOUS");
        Response current = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&type=SEMANTIC");
        assertThat(current.body().path("items")).hasSize(1);
        assertThat(current.body().path("items").get(0).path("value").asText()).isEqualTo("postgresql");
    }

    @Test
    void preferencesAreStoredPerUser() {
        Response r = say("I prefer Kotlin over Java.");
        JsonNode memory = r.body().path("extracted").get(0).path("memory");
        assertThat(memory.path("scope").asText()).isEqualTo("USER");
        assertThat(memory.path("userId").asText()).isNotBlank();
    }

    @Test
    void retriedMessageWithSameIdempotencyKeyIsStoredOnce() {
        String key = uniqueKey();
        Response a = alice.post("/api/v1/conversations/" + conversation + "/messages", Map.of("content", "We use Redis for caching."), key);
        Response b = alice.post("/api/v1/conversations/" + conversation + "/messages", Map.of("content", "We use Redis for caching."), key);
        assertThat(a.status()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(200);
        assertThat(b.body().path("replayed").asBoolean()).isTrue();
        assertThat(b.body().path("message").path("id").asText()).isEqualTo(a.body().path("message").path("id").asText());
        assertThat(alice.get("/api/v1/conversations/" + conversation).body().path("messageCount").asInt()).isEqualTo(1);
        assertThat(alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false").body().path("items")).hasSize(1);
    }

    @Test
    void summaryIsBuiltFromExtractedMemories() {
        say("We use Go. Never deploy on Fridays.");
        Response s = alice.post("/api/v1/conversations/" + conversation + "/summarize", null);
        assertThat(s.status()).isEqualTo(200);
        assertThat(s.text("summary")).contains("1 message(s)").contains("Go").contains("Never deploy on Fridays");
    }

    @Test
    void unknownConversationIs404() {
        Response r = alice.post("/api/v1/conversations/00000000-0000-0000-0000-000000000000/messages", Map.of("content", "hi"));
        assertThat(r.status()).isEqualTo(404);
    }
}
