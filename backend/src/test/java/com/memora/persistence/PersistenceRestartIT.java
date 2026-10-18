package com.memora.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.MemoraApplication;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.TestDatabase;
import com.memora.support.TestFalkorDB;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Writes through one backend instance, shuts it down, starts a new one and reads everything back. */
class PersistenceRestartIT {

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(MemoraApplication.class)
                .profiles("test")
                // Command-line arguments outrank application.yml (builder "properties" would not).
                .run("--server.port=0",
                        "--memora.test.clean-db=false",
                        "--spring.datasource.url=" + TestDatabase.URL,
                        "--spring.datasource.username=" + TestDatabase.USER,
                        "--spring.datasource.password=" + TestDatabase.PASSWORD,
                        "--memora.graph.host=" + TestFalkorDB.HOST,
                        "--memora.graph.port=" + TestFalkorDB.PORT);
    }

    private static ApiClient login(ConfigurableApplicationContext ctx) {
        int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
        Response r = ApiClient.anonymous(port).post("/api/v1/auth/login", Map.of("email", "alice@acme.test", "password", "memora-demo"));
        assertThat(r.status()).isEqualTo(200);
        return ApiClient.anonymous(port).withBearer(r.text("token"));
    }

    @Test
    void conversationsMemoriesTasksAndHandoffsSurviveARestart() {
        String conversationId;
        String memoryId;
        String taskId;
        try (ConfigurableApplicationContext first = start()) {
            ApiClient alice = login(first);
            conversationId = alice.post("/api/v1/conversations", Map.of("title", "Restart check")).text("id");
            alice.post("/api/v1/conversations/" + conversationId + "/messages", Map.of("content", "We use Elixir."));
            alice.post("/api/v1/conversations/" + conversationId + "/messages", Map.of("content", "Second message", "extract", false));
            memoryId = alice.post("/api/v1/memories", Map.of("type", "PROCEDURAL", "content", "Restart-proof procedure " + System.nanoTime()))
                    .body().path("memory").path("id").asText();
            taskId = alice.post("/api/v1/tasks", Map.of("title", "Restart-proof task", "state", Map.of("step", 3))).text("id");
        }

        try (ConfigurableApplicationContext second = start()) {
            ApiClient alice = login(second);
            JsonNode messages = alice.get("/api/v1/conversations/" + conversationId + "/messages").body().path("items");
            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).path("content").asText()).isEqualTo("We use Elixir.");
            assertThat(messages.get(1).path("seq").asInt()).isEqualTo(2);
            assertThat(alice.get("/api/v1/memories/" + memoryId).status()).isEqualTo(200);
            assertThat(alice.get("/api/v1/memories?conversationId=" + conversationId).body().findValuesAsText("value")).contains("elixir");
            assertThat(alice.get("/api/v1/tasks/" + taskId).body().path("state").path("step").asInt()).isEqualTo(3);
            // A new message continues the sequence instead of restarting it.
            Response next = alice.post("/api/v1/conversations/" + conversationId + "/messages", Map.of("content", "After restart", "extract", false));
            assertThat(next.body().path("message").path("seq").asInt()).isEqualTo(3);
        }
    }
}
