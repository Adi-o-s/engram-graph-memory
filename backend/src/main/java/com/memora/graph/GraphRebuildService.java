package com.memora.graph;

import com.memora.graph.GraphOutbox.Row;
import com.memora.security.Actor;
import com.memora.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reconstructs a tenant's graph from PostgreSQL: deletes the graph, re-projects every entity, then replays the
 * recorded reads. The graph is therefore always disposable; PostgreSQL is the system of record. Alerts are
 * not replayed (they are notifications about past changes, not state).
 */
@Service
public class GraphRebuildService {

    private static final Map<String, String> TABLES = new java.util.LinkedHashMap<>();

    static {
        TABLES.put("PROJECT", "projects");
        TABLES.put("AGENT", "agents");
        TABLES.put("CONVERSATION", "conversations");
        TABLES.put("MESSAGE", "messages");
        TABLES.put("TASK", "tasks");
        TABLES.put("MEMORY", "memories");
        TABLES.put("HANDOFF", "handoffs");
        TABLES.put("RUN", "workflow_runs");
        TABLES.put("STEP", "workflow_step_runs");
    }

    public record RebuildResult(String graph, Map<String, Integer> projected, int readsReplayed, Map<String, Long> counts,
                                long millis) {
    }

    private final GraphClient graph;
    private final TenantGraphs graphs;
    private final GraphProjection projection;
    private final GraphOutbox outbox;
    private final GraphProjector projector;
    private final GraphExplainService explain;
    private final JdbcTemplate jdbc;

    public GraphRebuildService(GraphClient graph, TenantGraphs graphs, GraphProjection projection, GraphOutbox outbox,
                               GraphProjector projector, GraphExplainService explain, JdbcTemplate jdbc) {
        this.graph = graph;
        this.graphs = graphs;
        this.projection = projection;
        this.outbox = outbox;
        this.projector = projector;
        this.explain = explain;
        this.jdbc = jdbc;
    }

    public RebuildResult rebuild(Actor actor) {
        long start = System.currentTimeMillis();
        UUID tenantId = actor.tenantId();
        // Pending rows describe changes the rebuild re-reads anyway; drain them first so nothing races the rebuild.
        projector.catchUp(tenantId);
        String g = TenantGraphs.nameFor(tenantId);
        graph.deleteGraph(g);
        graphs.forget(tenantId);
        graphs.ensure(tenantId);
        Map<String, Integer> projected = new java.util.LinkedHashMap<>();
        int[] reads = {0};
        TenantContext.runAsSystem(() -> {
            TABLES.forEach((kind, table) -> {
                List<UUID> ids = jdbc.queryForList("SELECT id FROM " + table + " WHERE tenant_id = ? ORDER BY created_at, id",
                        UUID.class, tenantId);
                for (int i = 0; i < ids.size(); i += 200) {
                    projection.project(g, tenantId, kind, ids.subList(i, Math.min(ids.size(), i + 200)));
                }
                projected.put(kind, ids.size());
            });
            for (Row r : outbox.appliedReads(tenantId)) {
                projection.projectRead(g, projection.parse(r.payload()));
                reads[0]++;
            }
        });
        return new RebuildResult(g, projected, reads[0], explain.counts(g), System.currentTimeMillis() - start);
    }
}
