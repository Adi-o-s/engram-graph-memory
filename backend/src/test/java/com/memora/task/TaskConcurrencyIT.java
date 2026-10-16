package com.memora.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskConcurrencyIT extends IntegrationTest {

    private ApiClient alice;

    @BeforeEach
    void setUp() {
        alice = as(ALICE);
    }

    private JsonNode createTask(Map<String, Object> state) {
        Response r = alice.post("/api/v1/tasks", Map.of("title", "Concurrency test task", "state", state));
        assertThat(r.status()).isEqualTo(201);
        return r.body();
    }

    @Test
    void staleVersionIsRejectedWithTheCurrentVersion() {
        JsonNode task = createTask(Map.of());
        String id = task.path("id").asText();
        Response first = alice.patch("/api/v1/tasks/" + id, Map.of("expectedVersion", 0, "statePatch", Map.of("a", 1)));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().path("version").asLong()).isEqualTo(1);
        Response stale = alice.patch("/api/v1/tasks/" + id, Map.of("expectedVersion", 0, "statePatch", Map.of("a", 2)));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("code")).isEqualTo("stale_version");
        assertThat(stale.body().path("currentVersion").asLong()).isEqualTo(1);
        assertThat(alice.get("/api/v1/tasks/" + id).body().path("state").path("a").asInt()).isEqualTo(1);
    }

    @Test
    void concurrentWritersOfTheSameVersionProduceExactlyOneWinner() throws Exception {
        String id = createTask(Map.of("owner", "nobody")).path("id").asText();
        int writers = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                start.await();
                return alice.patch("/api/v1/tasks/" + id, Map.of("expectedVersion", 0, "statePatch", Map.of("owner", "writer-" + n))).status();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(writers - 1);
        JsonNode finalTask = alice.get("/api/v1/tasks/" + id).body();
        assertThat(finalTask.path("version").asLong()).isEqualTo(1);
    }

    @Test
    void readModifyWriteWithRetriesNeverLosesAnUpdate() throws Exception {
        String id = createTask(Map.of("counter", 0)).path("id").asText();
        int workers = 8;
        int incrementsEach = 10;
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Callable<Void>> jobs = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            jobs.add(() -> {
                for (int i = 0; i < incrementsEach; i++) {
                    while (true) {
                        JsonNode t = alice.get("/api/v1/tasks/" + id).body();
                        int counter = t.path("state").path("counter").asInt();
                        Response r = alice.patch("/api/v1/tasks/" + id, Map.of("expectedVersion", t.path("version").asLong(),
                                "statePatch", Map.of("counter", counter + 1)));
                        if (r.status() == 200) {
                            break;
                        }
                        assertThat(r.status()).isEqualTo(409);
                        conflicts.incrementAndGet();
                        Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10));
                    }
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(jobs)) {
            f.get();
        }
        pool.shutdown();
        JsonNode finalTask = alice.get("/api/v1/tasks/" + id).body();
        assertThat(finalTask.path("state").path("counter").asInt()).isEqualTo(workers * incrementsEach);
        assertThat(finalTask.path("version").asLong()).isEqualTo(workers * incrementsEach);
        assertThat(conflicts.get()).as("contention should have produced conflicts").isGreaterThan(0);
    }

    @Test
    void invalidStatusTransitionsAreRejectedAndHistoryIsRecorded() {
        String id = createTask(Map.of()).path("id").asText();
        Response skip = alice.patch("/api/v1/tasks/" + id, Map.of("expectedVersion", 0, "status", "DONE"));
        assertThat(skip.status()).isEqualTo(409);
        assertThat(skip.text("code")).isEqualTo("invalid_transition");
        alice.patch("/api/v1/tasks/" + id, Map.of("expectedVersion", 0, "status", "IN_PROGRESS", "note", "starting"));
        JsonNode events = alice.get("/api/v1/tasks/" + id + "/events").body();
        assertThat(events).hasSize(2);
        assertThat(events.get(1).path("eventType").asText()).isEqualTo("status_changed");
        assertThat(events.get(1).path("toStatus").asText()).isEqualTo("IN_PROGRESS");
        assertThat(events.get(1).path("payload").path("note").asText()).isEqualTo("starting");
    }

    @Test
    void updateWithoutExpectedVersionIsInvalid() {
        String id = createTask(Map.of()).path("id").asText();
        assertThat(alice.patch("/api/v1/tasks/" + id, Map.of("title", "x")).status()).isEqualTo(400);
    }

    @Test
    void idempotentTaskCreation() {
        String key = uniqueKey();
        Response a = alice.post("/api/v1/tasks", Map.of("title", "Once only"), key);
        Response b = alice.post("/api/v1/tasks", Map.of("title", "Once only"), key);
        assertThat(b.text("id")).isEqualTo(a.text("id"));
    }
}
