package com.memora.graph;

import com.memora.security.Actor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Stale-knowledge alerts, stored as {@code (:Alert)} nodes in the tenant graph. When a memory stops being
 * CURRENT (superseded or retracted), the projector walks the graph to find who is now working from it:
 * agents that READ it, runs still in progress whose steps USED it, and open handoffs that CARRY it.
 */
@Component
public class GraphAlerts {

    static final String RAISE_FOR_READERS = """
            UNWIND $ids AS id
            MATCH (old:Memory {id: id})<-[rd:READ]-(a:Agent)
            WHERE old.status <> 'CURRENT'
            MERGE (al:Alert {id: id + '|agent|' + a.id})
            ON CREATE SET al.status = 'OPEN', al.kind = 'READ_BY_AGENT', al.createdAt = $now, al.memoryId = id,
                          al.change = old.status, al.readAt = rd.lastAt
            MERGE (al)-[:NOTIFIES]->(a)
            MERGE (al)-[:CONCERNS]->(old)
            RETURN count(al) AS n""";
    static final String RAISE_FOR_RUNS = """
            UNWIND $ids AS id
            MATCH (old:Memory {id: id})<-[:USED]-(s:Step)<-[:HAS_STEP]-(run:Run)
            WHERE old.status <> 'CURRENT' AND run.status IN ['PENDING', 'RUNNING', 'WAITING_APPROVAL', 'PAUSED', 'BLOCKED']
            MERGE (al:Alert {id: id + '|run|' + run.id})
            ON CREATE SET al.status = 'OPEN', al.kind = 'USED_BY_ACTIVE_RUN', al.createdAt = $now, al.memoryId = id,
                          al.change = old.status
            MERGE (al)-[:NOTIFIES]->(run)
            MERGE (al)-[:CONCERNS]->(old)
            RETURN count(al) AS n""";
    static final String RAISE_FOR_HANDOFFS = """
            UNWIND $ids AS id
            MATCH (old:Memory {id: id})<-[:CARRIES]-(h:Handoff)-[:TO]->(a:Agent)
            WHERE old.status <> 'CURRENT' AND h.status IN ['PENDING', 'ACKNOWLEDGED']
            MERGE (al:Alert {id: id + '|handoff|' + h.id})
            ON CREATE SET al.status = 'OPEN', al.kind = 'CARRIED_BY_OPEN_HANDOFF', al.createdAt = $now, al.memoryId = id,
                          al.change = old.status
            MERGE (al)-[:NOTIFIES]->(a)
            MERGE (al)-[:VIA]->(h)
            MERGE (al)-[:CONCERNS]->(old)
            RETURN count(al) AS n""";
    static final String OPEN_FOR_AGENT = """
            MATCH (al:Alert {status: 'OPEN'})-[:NOTIFIES]->(a:Agent {id: $agentId})
            MATCH (al)-[:CONCERNS]->(old:Memory)
            OPTIONAL MATCH (nw:Memory {status: 'CURRENT'})-[:SUPERSEDES*1..10]->(old)
            OPTIONAL MATCH (al)-[:VIA]->(h:Handoff)
            RETURN al.id AS alertId, al.kind AS kind, al.createdAt AS createdAt, a.name AS agentName,
                   old.id AS oldId, old.content AS oldContent, old.status AS oldStatus, old.scopeKey AS oldScope,
                   nw.id AS newId, nw.content AS newContent, h.id AS handoffId
            ORDER BY al.createdAt
            LIMIT 20""";
    static final String MARK_DELIVERED = """
            UNWIND $ids AS id
            MATCH (al:Alert {id: id})
            SET al.status = 'DELIVERED', al.deliveredAt = $now
            RETURN count(al) AS n""";
    static final String LIST = """
            MATCH (al:Alert)-[:CONCERNS]->(old:Memory)
            MATCH (al)-[:NOTIFIES]->(t)
            OPTIONAL MATCH (nw:Memory {status: 'CURRENT'})-[:SUPERSEDES*1..10]->(old)
            RETURN al.id AS alertId, al.kind AS kind, al.status AS status, al.createdAt AS createdAt,
                   al.deliveredAt AS deliveredAt, labels(t)[0] AS targetLabel, t.id AS targetId,
                   coalesce(t.name, t.id) AS targetName, old.id AS oldId, old.content AS oldContent,
                   old.status AS oldStatus, old.scopeKey AS oldScope, nw.id AS newId, nw.content AS newContent
            ORDER BY al.createdAt DESC
            LIMIT $limit""";

    public record Alert(String alertId, String kind, String status, Long createdAt, Long deliveredAt, String targetLabel,
                        String targetId, String targetName, String oldMemoryId, String oldContent, String oldStatus,
                        String newMemoryId, String newContent, String handoffId, List<GraphPath.Step> path) {
    }

    private final GraphClient graph;
    private final TenantGraphs graphs;

    public GraphAlerts(GraphClient graph, TenantGraphs graphs) {
        this.graph = graph;
        this.graphs = graphs;
    }

    void raise(String g, List<String> changedMemoryIds) {
        if (changedMemoryIds.isEmpty()) {
            return;
        }
        Map<String, Object> p = Map.of("ids", changedMemoryIds, "now", System.currentTimeMillis());
        graph.write(g, "alerts.raise.readers", RAISE_FOR_READERS, p);
        graph.write(g, "alerts.raise.runs", RAISE_FOR_RUNS, p);
        graph.write(g, "alerts.raise.handoffs", RAISE_FOR_HANDOFFS, p);
    }

    /** Open alerts for the calling agent; they are marked delivered once shown in a context bundle. */
    public List<Alert> openFor(Actor actor) {
        if (actor.agentId() == null) {
            return List.of();
        }
        String g = graphs.of(actor);
        List<Alert> out = new ArrayList<>();
        for (Map<String, Object> r : graph.read(g, "alerts.open-for-agent", OPEN_FOR_AGENT,
                Map.of("agentId", actor.agentId().toString()))) {
            if (!visibleScope(actor, (String) r.get("oldScope"))) {
                continue;
            }
            out.add(new Alert((String) r.get("alertId"), (String) r.get("kind"), "OPEN", num(r.get("createdAt")), null, "Agent",
                    actor.agentId().toString(), (String) r.get("agentName"), (String) r.get("oldId"), (String) r.get("oldContent"),
                    (String) r.get("oldStatus"), (String) r.get("newId"), (String) r.get("newContent"),
                    (String) r.get("handoffId"), path("Agent", (String) r.get("agentName"), r)));
        }
        return out;
    }

    public void markDelivered(Actor actor, List<String> alertIds) {
        if (!alertIds.isEmpty()) {
            graph.write(graphs.of(actor), "alerts.delivered", MARK_DELIVERED,
                    Map.of("ids", alertIds, "now", System.currentTimeMillis()));
        }
    }

    public List<Alert> list(Actor actor, int limit) {
        List<Alert> out = new ArrayList<>();
        for (Map<String, Object> r : graph.read(graphs.of(actor), "alerts.list", LIST, Map.of("limit", limit))) {
            if (!visibleScope(actor, (String) r.get("oldScope"))) {
                continue;
            }
            out.add(new Alert((String) r.get("alertId"), (String) r.get("kind"), (String) r.get("status"),
                    num(r.get("createdAt")), num(r.get("deliveredAt")), (String) r.get("targetLabel"), (String) r.get("targetId"),
                    String.valueOf(r.get("targetName")), (String) r.get("oldId"), (String) r.get("oldContent"),
                    (String) r.get("oldStatus"), (String) r.get("newId"), (String) r.get("newContent"), null,
                    path((String) r.get("targetLabel"), String.valueOf(r.get("targetName")), r)));
        }
        return out;
    }

    private static List<GraphPath.Step> path(String targetLabel, String targetName, Map<String, Object> r) {
        List<GraphPath.Step> steps = new ArrayList<>();
        String kind = String.valueOf(r.get("kind"));
        String rel = switch (kind) {
            case "USED_BY_ACTIVE_RUN" -> "HAS_STEP/USED";
            case "CARRIED_BY_OPEN_HANDOFF" -> "TO/CARRIES";
            default -> "READ";
        };
        steps.add(new GraphPath.Step(targetLabel, null, targetName, null));
        steps.add(new GraphPath.Step("Memory", (String) r.get("oldId"), (String) r.get("oldContent"), rel));
        if (r.get("newId") != null) {
            steps.add(new GraphPath.Step("Memory", (String) r.get("newId"), (String) r.get("newContent"), "SUPERSEDED_BY"));
        }
        return steps;
    }

    static boolean visibleScope(Actor actor, String scopeKey) {
        return scopeKey == null || actor.seesAllUserData() || !scopeKey.startsWith("user:")
                || scopeKey.equals("user:" + actor.userId());
    }

    private static Long num(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
