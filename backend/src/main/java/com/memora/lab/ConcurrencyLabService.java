package com.memora.lab;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.common.RequestMetrics;
import com.memora.conversation.ConversationDtos.ConversationView;
import com.memora.conversation.ConversationService;
import com.memora.conversation.MessageRole;
import com.memora.extraction.MessageIngestionService;
import com.memora.lab.LabDtos.Invariant;
import com.memora.lab.LabDtos.LabRequest;
import com.memora.lab.LabDtos.LabResult;
import com.memora.lab.LabDtos.LatencyStats;
import com.memora.lab.LabDtos.Scenario;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryQuery;
import com.memora.memory.MemorySearchService;
import com.memora.memory.MemoryService;
import com.memora.memory.MemoryStatus;
import com.memora.memory.MemoryType;
import com.memora.memory.SourceType;
import com.memora.security.Actor;
import com.memora.security.TenantContext;
import com.memora.task.TaskDtos.CreateTaskRequest;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskDtos.UpdateTaskRequest;
import com.memora.task.TaskService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Runs a real concurrent workload against the service layer and PostgreSQL inside this backend
 * process, then checks consistency invariants against the database. Numbers are measured, not
 * modelled: they reflect this machine, this database and this configuration only.
 */
@Service
public class ConcurrencyLabService {

    private static final int MAX_RETRIES = 100;

    private final TaskService tasks;
    private final ConversationService conversations;
    private final MessageIngestionService ingestion;
    private final MemoryService memories;
    private final MemorySearchService search;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final LabProperties properties;

    public ConcurrencyLabService(TaskService tasks, ConversationService conversations, MessageIngestionService ingestion,
                                 MemoryService memories, MemorySearchService search, JdbcTemplate jdbc,
                                 ObjectMapper objectMapper, LabProperties properties) {
        this.tasks = tasks;
        this.conversations = conversations;
        this.ingestion = ingestion;
        this.memories = memories;
        this.search = search;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public LabResult run(Actor actor, LabRequest request) {
        if (request.threads() > properties.maxThreads() || request.operations() > properties.maxOperations()) {
            throw ApiException.badRequest("Limits: threads <= " + properties.maxThreads() + ", operations <= "
                    + properties.maxOperations());
        }
        LabResult result = switch (request.scenario()) {
            case TASK_COUNTER -> taskCounter(actor, request);
            case MESSAGE_APPEND -> messageAppend(actor, request);
            case MEMORY_WRITES -> memoryWrites(actor, request);
        };
        jdbc.update("INSERT INTO lab_runs (id, tenant_id, scenario, threads, operations, results, created_by_user_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                result.runId(), actor.tenantId(), request.scenario().name(), request.threads(), request.operations(),
                toJson(result), actor.userId(), Timestamp.from(result.startedAt()));
        return result;
    }

    public List<LabResult> recent(Actor actor, int limit) {
        return jdbc.query("SELECT results::text FROM lab_runs WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> fromJson(rs.getString(1)), actor.tenantId(), limit);
    }

    // ---------------------------------------------------------------- scenarios

    private LabResult taskCounter(Actor actor, LabRequest request) {
        TaskView task = tasks.create(actor, new CreateTaskRequest(null, "Concurrency lab counter " + Instant.now(),
                "Created by the concurrency lab", 5, null, Map.of("counter", 0, "lab", true)), null);
        AtomicLong conflicts = new AtomicLong();
        AtomicLong retries = new AtomicLong();
        Workload w = execute(request, () -> {
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                TaskView current = tasks.get(actor, task.id());
                int counter = ((Number) current.state().get("counter")).intValue();
                try {
                    tasks.update(actor, task.id(), new UpdateTaskRequest(current.version(), null, null, null, null, null,
                            null, Map.of("counter", counter + 1), null));
                    return;
                } catch (ApiException e) {
                    if (!"stale_version".equals(e.code())) {
                        throw e;
                    }
                    conflicts.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    conflicts.incrementAndGet();
                }
                retries.incrementAndGet();
                // Exponential backoff with jitter keeps retry storms short under heavy contention.
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 2L + Math.min(50, 1L << Math.min(attempt, 6))));
            }
            throw new IllegalStateException("Gave up after " + MAX_RETRIES + " stale-version retries");
        });
        TaskView finalTask = tasks.get(actor, task.id());
        long counter = ((Number) finalTask.state().get("counter")).longValue();
        List<Invariant> invariants = List.of(
                invariant("No lost updates: counter equals successful updates", w.succeeded, counter),
                invariant("Task version advanced once per successful update", w.succeeded, finalTask.version()));
        return result(request, w, conflicts.get(), retries.get(), invariants,
                Json.map("taskId", task.id(), "finalCounter", counter, "finalVersion", finalTask.version()));
    }

    private LabResult messageAppend(Actor actor, LabRequest request) {
        ConversationView conversation = conversations.create(actor, null, "Concurrency lab " + Instant.now(), "lab", null);
        AtomicInteger n = new AtomicInteger();
        Workload w = execute(request, () -> ingestion.append(actor, conversation.id(), MessageRole.USER,
                "Lab message " + n.incrementAndGet(), Map.of("lab", true), false, null));
        Long stored = jdbc.queryForObject("SELECT count(*) FROM messages WHERE tenant_id = ? AND conversation_id = ?",
                Long.class, actor.tenantId(), conversation.id());
        Long distinctSeq = jdbc.queryForObject("SELECT count(DISTINCT seq) FROM messages WHERE tenant_id = ? AND conversation_id = ?",
                Long.class, actor.tenantId(), conversation.id());
        Long maxSeq = jdbc.queryForObject("SELECT coalesce(max(seq), 0) FROM messages WHERE tenant_id = ? AND conversation_id = ?",
                Long.class, actor.tenantId(), conversation.id());
        Long counter = jdbc.queryForObject("SELECT message_count FROM conversations WHERE tenant_id = ? AND id = ?",
                Long.class, actor.tenantId(), conversation.id());
        List<Invariant> invariants = List.of(
                invariant("Every successful append is stored", w.succeeded, stored),
                invariant("Sequence numbers are unique", stored, distinctSeq),
                invariant("Sequence numbers are gapless (max seq = count)", stored, maxSeq),
                invariant("Atomic message counter matches stored messages", stored, counter));
        return result(request, w, 0, 0, invariants, Json.map("conversationId", conversation.id(), "storedMessages", stored));
    }

    private LabResult memoryWrites(Actor actor, LabRequest request) {
        String runTag = "lab-" + UUID.randomUUID().toString().substring(0, 8);
        String predicate = "lab." + runTag;
        AtomicInteger n = new AtomicInteger();
        AtomicLong episodesWritten = new AtomicLong();
        Workload w = execute(request, () -> {
            int i = n.incrementAndGet();
            if (i % 2 == 0) {
                // Competing writers of the same fact with a handful of distinct values.
                String value = "value-" + (i % 5);
                memories.create(actor, MemoryDraft.builder(MemoryType.SEMANTIC, "Lab fact " + runTag + " is " + value)
                        .category("fact").fact("lab", predicate, value).tags(List.of(runTag))
                        .source(SourceType.SYSTEM, null, "concurrency-lab").build(), null);
            } else {
                memories.create(actor, MemoryDraft.builder(MemoryType.EPISODIC, "Lab event " + runTag + " #" + i)
                        .category("event").tags(List.of(runTag)).source(SourceType.SYSTEM, null, "concurrency-lab").build(), null);
                episodesWritten.incrementAndGet();
            }
        });
        var all = search.search(actor, MemoryQuery.builder().tags(List.of(runTag)).includeHistory(true).limit(100).build());
        Long currentFacts = jdbc.queryForObject(
                "SELECT count(*) FROM memories WHERE tenant_id = ? AND fact_key = ? AND status = 'CURRENT'",
                Long.class, actor.tenantId(), "lab|" + predicate);
        Long episodes = jdbc.queryForObject(
                "SELECT count(*) FROM memories WHERE tenant_id = ? AND memory_type = 'EPISODIC' AND ? = ANY(tags)",
                Long.class, actor.tenantId(), runTag);
        Long brokenChains = jdbc.queryForObject("""
                SELECT count(*) FROM memories m WHERE m.tenant_id = ? AND m.fact_key = ? AND m.status = 'SUPERSEDED'
                  AND (m.superseded_by_id IS NULL
                       OR NOT EXISTS (SELECT 1 FROM memories s WHERE s.id = m.superseded_by_id AND s.supersedes_id = m.id))
                """, Long.class, actor.tenantId(), "lab|" + predicate);
        List<Invariant> invariants = List.of(
                invariant("Exactly one CURRENT version of the contested fact", 1, currentFacts),
                invariant("Every superseded version links to its successor", 0, brokenChains),
                invariant("Every successful episodic write is stored exactly once", episodesWritten.get(), episodes));
        return result(request, w, 0, 0, invariants, Json.map("tag", runTag, "factKey", "lab|" + predicate,
                "versionsOfFact", all.items().stream().filter(m -> m.status() != null && m.factKey() != null).count(),
                "currentFactValue", all.items().stream().filter(m -> m.factKey() != null && m.status() == MemoryStatus.CURRENT)
                        .map(m -> m.value()).findFirst().orElse(null)));
    }

    // ---------------------------------------------------------------- harness

    private static final class Workload {
        long succeeded;
        long failed;
        long durationMs;
        List<Double> latencies = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Instant startedAt;
    }

    private interface Op {
        void run() throws Exception;
    }

    private Workload execute(LabRequest request, Op op) {
        Workload w = new Workload();
        ConcurrentLinkedQueue<Double> latencies = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        AtomicLong ok = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicInteger remaining = new AtomicInteger(request.operations());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(request.threads());
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int t = 0; t < request.threads(); t++) {
                Callable<Void> worker = () -> {
                    start.await();
                    while (remaining.getAndDecrement() > 0) {
                        long t0 = System.nanoTime();
                        try {
                            op.run();
                            ok.incrementAndGet();
                        } catch (Exception e) {
                            failed.incrementAndGet();
                            if (errors.size() < 10) {
                                errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                            }
                        }
                        latencies.add((System.nanoTime() - t0) / 1_000_000.0);
                    }
                    return null;
                };
                futures.add(pool.submit(TenantContext.propagate(worker)));
            }
            w.startedAt = Instant.now();
            long t0 = System.nanoTime();
            start.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }
            w.durationMs = (System.nanoTime() - t0) / 1_000_000;
        } catch (Exception e) {
            throw new IllegalStateException("Lab run interrupted: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
        w.succeeded = ok.get();
        w.failed = failed.get();
        w.latencies = new ArrayList<>(latencies);
        w.errors = new ArrayList<>(errors);
        return w;
    }

    private static LabResult result(LabRequest request, Workload w, long conflicts, long retries, List<Invariant> invariants,
                                    Map<String, Object> target) {
        List<Double> sorted = new ArrayList<>(w.latencies);
        Collections.sort(sorted);
        double mean = sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        LatencyStats latency = new LatencyStats(RequestMetrics.percentileOf(sorted, 50), RequestMetrics.percentileOf(sorted, 95),
                RequestMetrics.percentileOf(sorted, 99), sorted.isEmpty() ? 0 : RequestMetrics.round(sorted.get(sorted.size() - 1)),
                RequestMetrics.round(mean));
        double throughput = w.durationMs == 0 ? 0 : RequestMetrics.round(w.succeeded * 1000.0 / w.durationMs);
        return new LabResult(UUID.randomUUID(), request.scenario(), request.threads(), request.operations(), w.succeeded,
                w.failed, conflicts, retries, w.durationMs, throughput, latency, invariants,
                invariants.stream().allMatch(Invariant::passed), w.errors, target, w.startedAt);
    }

    private static Invariant invariant(String name, long expected, Long actual) {
        return new Invariant(name, String.valueOf(expected), String.valueOf(actual), actual != null && expected == actual);
    }

    private String toJson(LabResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LabResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
