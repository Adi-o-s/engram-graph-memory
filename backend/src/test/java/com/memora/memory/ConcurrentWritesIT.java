package com.memora.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Concurrent message and memory creation through the HTTP API. */
class ConcurrentWritesIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private <T> List<T> runConcurrently(int threads, int tasks, java.util.function.IntFunction<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<T>> jobs = new ArrayList<>();
        for (int i = 0; i < tasks; i++) {
            int n = i;
            jobs.add(() -> work.apply(n));
        }
        List<T> out = new ArrayList<>();
        for (Future<T> f : pool.invokeAll(jobs)) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    @Test
    void concurrentMessagesGetUniqueGaplessSequenceNumbers() throws Exception {
        ApiClient alice = as(ALICE);
        String conversation = newConversation(alice, null);
        List<Integer> statuses = runConcurrently(16, 80, n -> alice.post("/api/v1/conversations/" + conversation + "/messages",
                Map.of("content", "parallel message " + n, "extract", false)).status());
        assertThat(statuses).containsOnly(201);
        Response page = alice.get("/api/v1/conversations/" + conversation + "/messages?size=200");
        Set<Long> seqs = new HashSet<>();
        page.body().path("items").forEach(m -> seqs.add(m.path("seq").asLong()));
        assertThat(seqs).hasSize(80);
        assertThat(seqs.stream().mapToLong(Long::longValue).max().orElse(0)).isEqualTo(80);
        assertThat(alice.get("/api/v1/conversations/" + conversation).body().path("messageCount").asInt()).isEqualTo(80);
    }

    @Test
    void concurrentWritersOfOneFactLeaveExactlyOneCurrentVersion() throws Exception {
        ApiClient alice = as(ALICE);
        String project = newProject(alice, "race");
        List<Integer> statuses = runConcurrently(12, 60, n -> alice.post("/api/v1/memories", Map.of(
                "type", "SEMANTIC", "content", "Release train is " + (n % 4), "subject", "team", "predicate", "release.train",
                "value", "train-" + (n % 4), "projectId", project)).status());
        assertThat(statuses).allMatch(s -> s == 200 || s == 201);
        Response current = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false");
        assertThat(current.body().path("items")).hasSize(1);
        Response all = alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false&includeHistory=true&limit=100");
        List<Integer> versions = all.body().path("items").findValuesAsText("version").stream().map(Integer::parseInt).sorted().toList();
        // Versions form one unbroken chain 1..n.
        for (int i = 0; i < versions.size(); i++) {
            assertThat(versions.get(i)).isEqualTo(i + 1);
        }
    }

    @Test
    void concurrentRetriesWithOneIdempotencyKeyCreateOneRecord() throws Exception {
        ApiClient alice = as(ALICE);
        String project = newProject(alice, "idem");
        String key = uniqueKey();
        List<String> ids = runConcurrently(10, 10, n -> alice.post("/api/v1/memories",
                Map.of("type", "EPISODIC", "content", "Exactly once " + key, "projectId", project), key)
                .body().path("memory").path("id").asText());
        assertThat(new HashSet<>(ids)).hasSize(1);
        assertThat(alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false").body().path("total").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentDistinctMemoriesAreAllStored() throws Exception {
        ApiClient alice = as(ALICE);
        String project = newProject(alice, "bulk");
        List<Integer> statuses = runConcurrently(16, 64, n -> alice.post("/api/v1/memories",
                Map.of("type", "EPISODIC", "content", "Distinct event " + n, "projectId", project)).status());
        assertThat(statuses).containsOnly(201);
        assertThat(alice.get("/api/v1/memories?projectId=" + project + "&includeShared=false").body().path("total").asInt()).isEqualTo(64);
    }
}
