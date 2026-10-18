package com.memora.graph;

import com.memora.graph.GraphOutbox.Row;
import com.memora.security.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies {@code graph_outbox} rows to FalkorDB. A batch is claimed with {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * (the same pattern the workflow scheduler uses), so several backend instances can project in parallel without
 * applying a row twice at the same time; the locks are held until the rows are marked DONE. Projection re-reads
 * current state and MERGEs, so at-least-once delivery is safe. A failing batch is retried row by row, and a
 * failing row backs off exponentially without blocking the others.
 */
@Component
public class GraphProjector {

    private static final Logger log = LoggerFactory.getLogger(GraphProjector.class);
    /** Order inside a batch: referenced nodes first, so MATCHes in later statements find them. */
    private static final List<String> KIND_ORDER = List.of("PROJECT", "AGENT", "CONVERSATION", "MESSAGE", "TASK", "MEMORY",
            "HANDOFF", "RUN", "STEP");

    private final GraphOutbox outbox;
    private final GraphProjection projection;
    private final GraphAlerts alerts;
    private final TenantGraphs graphs;
    private final GraphProperties properties;
    private final TransactionTemplate tx;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicLong applied = new AtomicLong();

    public GraphProjector(GraphOutbox outbox, GraphProjection projection, GraphAlerts alerts, TenantGraphs graphs,
                          GraphProperties properties, TransactionTemplate tx) {
        this.outbox = outbox;
        this.projection = projection;
        this.alerts = alerts;
        this.graphs = graphs;
        this.properties = properties;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${memora.graph.poll-interval-ms:250}", initialDelay = 1000)
    public void poll() {
        if (!properties.projectorEnabled() || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            while (processBatch(null) >= properties.batchSize()) {
                // keep draining while full batches come back
            }
        } catch (RuntimeException e) {
            log.warn("Graph projection poll failed: {}", e.getMessage());
        } finally {
            polling.set(false);
        }
    }

    /**
     * Read-your-writes for graph queries: applies the tenant's pending rows before a traversal, so an agent asking
     * for context right after a write sees it. Bounded by {@code memora.graph.catch-up-timeout-ms}.
     */
    public void catchUp(UUID tenantId) {
        long deadline = System.currentTimeMillis() + properties.catchUpTimeoutMs();
        while (true) {
            int n = processBatch(tenantId);
            if (n > 0) {
                continue;
            }
            long pending = TenantContext.callAsSystem(() -> outbox.pendingFor(tenantId));
            if (pending == 0 || System.currentTimeMillis() > deadline) {
                return;
            }
            // Rows are locked by another worker (or backing off): give it a moment.
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public long appliedCount() {
        return applied.get();
    }

    /** Claims and applies one batch; returns how many rows were claimed. */
    int processBatch(UUID tenantId) {
        Integer n = TenantContext.callAsSystem(() -> tx.execute(s -> {
            List<Row> rows = outbox.claim(tenantId, properties.batchSize());
            if (rows.isEmpty()) {
                return 0;
            }
            Map<UUID, List<Row>> byTenant = new LinkedHashMap<>();
            rows.forEach(r -> byTenant.computeIfAbsent(r.tenantId(), k -> new ArrayList<>()).add(r));
            List<Long> done = new ArrayList<>();
            byTenant.forEach((tenant, tenantRows) -> {
                try {
                    apply(tenant, tenantRows, true);
                    tenantRows.forEach(r -> done.add(r.id()));
                } catch (RuntimeException batchError) {
                    log.debug("Graph batch for tenant {} failed ({}), retrying row by row", tenant, batchError.getMessage());
                    for (Row r : tenantRows) {
                        try {
                            apply(tenant, List.of(r), true);
                            done.add(r.id());
                        } catch (RuntimeException e) {
                            log.warn("Graph projection of {} {} failed (attempt {}): {}", r.kind(), r.entityId(), r.attempts() + 1,
                                    e.getMessage());
                            outbox.markFailed(r.id(), r.attempts(), properties.maxAttempts(), e.getMessage());
                        }
                    }
                }
            });
            outbox.markDone(done);
            applied.addAndGet(done.size());
            return rows.size();
        }));
        return n == null ? 0 : n;
    }

    /**
     * Projects rows of one tenant. Entity rows are coalesced per kind (the latest state is read anyway); READ rows
     * are applied in order after them. With {@code raiseAlerts}, memories that stopped being CURRENT trigger the
     * stale-knowledge traversal.
     */
    void apply(UUID tenantId, List<Row> rows, boolean raiseAlerts) {
        String g = graphs.ensure(tenantId);
        Map<String, Set<UUID>> byKind = new LinkedHashMap<>();
        List<Row> reads = new ArrayList<>();
        for (Row r : rows) {
            if ("READ".equals(r.kind())) {
                reads.add(r);
            } else if (r.entityId() != null) {
                byKind.computeIfAbsent(r.kind(), k -> new LinkedHashSet<>()).add(r.entityId());
            }
        }
        List<String> changed = new ArrayList<>();
        for (String kind : KIND_ORDER) {
            Set<UUID> ids = byKind.get(kind);
            if (ids != null) {
                changed.addAll(projection.project(g, tenantId, kind, ids));
            }
        }
        for (Row r : reads) {
            projection.projectRead(g, projection.parse(r.payload()));
        }
        if (raiseAlerts) {
            alerts.raise(g, changed);
        }
    }
}
