package com.memora.graph;

import com.memora.security.Actor;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One FalkorDB graph per tenant. The graph name is derived only from a tenant id that came from
 * authentication (an {@link Actor}) or from a tenant-owned outbox row, never from request input, so a
 * caller can never address another tenant's graph.
 */
@Component
public class TenantGraphs {

    private static final Logger log = LoggerFactory.getLogger(TenantGraphs.class);

    static final List<String> ID_LABELS = List.of("Memory", "Agent", "User", "Project", "Conversation", "Message", "Task",
            "Handoff", "Run", "Step", "Alert");

    private final GraphClient client;
    private final Set<String> prepared = ConcurrentHashMap.newKeySet();

    public TenantGraphs(GraphClient client) {
        this.client = client;
    }

    public static String nameFor(UUID tenantId) {
        return "memora_t_" + tenantId.toString().replace("-", "");
    }

    /** The caller's own graph, with its indexes in place. */
    public String of(Actor actor) {
        return ensure(actor.tenantId());
    }

    public String ensure(UUID tenantId) {
        String graph = nameFor(tenantId);
        if (prepared.add(graph)) {
            try {
                createIndexes(graph);
            } catch (RuntimeException e) {
                prepared.remove(graph);
                throw e;
            }
        }
        return graph;
    }

    /** Forgets the index cache for a graph that was just deleted (rebuild). */
    void forget(UUID tenantId) {
        prepared.remove(nameFor(tenantId));
    }

    private void createIndexes(String graph) {
        for (String label : ID_LABELS) {
            createIndex(graph, "CREATE INDEX FOR (n:" + label + ") ON (n.id)");
        }
        createIndex(graph, "CREATE INDEX FOR (e:Entity) ON (e.key)");
        createIndex(graph, "CALL db.idx.fulltext.createNodeIndex('Memory', 'content')");
    }

    private void createIndex(String graph, String statement) {
        try {
            client.write(graph, "index", statement, java.util.Map.of());
        } catch (RuntimeException e) {
            // Index statements are not idempotent in FalkorDB; "already indexed" means another node created it.
            String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (!msg.contains("already indexed") && !msg.contains("already exists")) {
                log.warn("Graph index statement failed on {}: {}", graph, e.getMessage());
                throw e;
            }
        }
    }
}
