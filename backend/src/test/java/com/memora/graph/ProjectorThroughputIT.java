package com.memora.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.security.TenantContext;
import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import com.memora.support.IntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Projector throughput and outbox lag under concurrent writes. 8 client threads write 400 memories through the
 * API while a sampler reads the outbox lag every 20 ms; the background projector (poll every 250 ms, batches of
 * 200) is the only thing draining the outbox. Results are written to target/projector-throughput.md.
 */
class ProjectorThroughputIT extends IntegrationTest {

    private static final int THREADS = 8;
    private static final int WRITES = 400;

    @Autowired
    GraphOutbox outbox;
    @Autowired
    GraphProjector projector;

    @Test
    void outboxDrainsUnderConcurrentWrites() throws Exception {
        ApiClient alice = as(ALICE);
        String project = newProject(alice, "projector-load");
        long appliedBefore = projector.appliedCount();
        long failedBefore = TenantContext.callAsSystem(outbox::lag).failed();
        AtomicBoolean sampling = new AtomicBoolean(true);
        AtomicLong maxPending = new AtomicLong();
        AtomicLong maxOldestMs = new AtomicLong();
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                GraphOutbox.Lag lag = TenantContext.callAsSystem(outbox::lag);
                maxPending.accumulateAndGet(lag.pending(), Math::max);
                if (lag.oldestPendingSeconds() != null) {
                    maxOldestMs.accumulateAndGet(Math.round(lag.oldestPendingSeconds() * 1000), Math::max);
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.start();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        long start = System.nanoTime();
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < WRITES; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                Response r = alice.post("/api/v1/memories", Map.of("type", "SEMANTIC", "projectId", project,
                        "content", "load-" + n + "-service uses Kafka for event number " + n + ".", "importance", 3));
                return r.status();
            }));
        }
        for (Future<Integer> f : results) {
            assertThat(f.get()).isEqualTo(201);
        }
        long writesDone = System.nanoTime();
        pool.shutdown();

        long drained = 0;
        while (System.nanoTime() - writesDone < 60_000_000_000L) {
            if (TenantContext.callAsSystem(outbox::lag).pending() == 0) {
                drained = System.nanoTime();
                break;
            }
            Thread.sleep(10);
        }
        sampling.set(false);
        sampler.join();
        assertThat(drained).as("outbox did not drain within 60 s").isPositive();

        long applied = projector.appliedCount() - appliedBefore;
        double writeSeconds = (writesDone - start) / 1e9;
        double totalSeconds = (drained - start) / 1e9;
        String md = String.format(Locale.ROOT, """
                | Measure | Value |
                |---|---|
                | Memories written through the API (%d threads) | %d |
                | Outbox rows applied by the projector | %d |
                | Write phase (all API calls returned) | %.2f s (%.0f writes/s) |
                | Outbox empty after the last write returned | %.0f ms |
                | Rows applied per second over the run (bounded by the write rate) | %.0f rows/s |
                | Highest pending rows seen (sampled every 20 ms) | %d |
                | Oldest pending row age, highest seen | %d ms |
                | New FAILED rows | %d |
                """, THREADS, WRITES, applied, writeSeconds, WRITES / writeSeconds, (drained - writesDone) / 1e6,
                applied / totalSeconds, maxPending.get(), maxOldestMs.get(), TenantContext.callAsSystem(outbox::lag).failed() - failedBefore);
        Path out = Path.of("target", "projector-throughput.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md);
        System.out.println("\n" + md);
        assertThat(TenantContext.callAsSystem(outbox::lag).failed()).isEqualTo(failedBefore);
    }
}
