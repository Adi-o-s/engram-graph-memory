package com.memora.graph;

import com.memora.common.ApiException;
import com.memora.memory.MemoryService;
import com.memora.security.Actor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * "Why does the agent know this?": the shortest path in the tenant graph from a starting point (a task, an
 * agent or an entity) to a memory, plus the memory's provenance (message and conversation it came from, who
 * wrote it, what it superseded and who read it). Also serves the graph explorer's node and edge lists.
 */
@Service
public class GraphExplainService {

    // FalkorDB has no undirected shortestPath(); an ordered, bounded variable-length match does the same job.
    private static final String PATH_TAIL = """
            MATCH p = (s)-[*1..4]-(m)
            WITH p ORDER BY length(p) LIMIT 1
            RETURN [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
                   [r IN relationships(p) | type(r)] AS rels""";
    static final String PATH_FROM_TASK = "MATCH (s:Task {id: $from}), (m:Memory {id: $memoryId})\n" + PATH_TAIL;
    static final String PATH_FROM_AGENT = "MATCH (s:Agent {id: $from}), (m:Memory {id: $memoryId})\n" + PATH_TAIL;
    static final String PATH_FROM_ENTITY = "MATCH (s:Entity {key: $from}), (m:Memory {id: $memoryId})\n" + PATH_TAIL;
    static final String PROVENANCE = """
            MATCH (m:Memory {id: $memoryId})
            OPTIONAL MATCH (m)-[:EXTRACTED_FROM]->(msg:Message)-[:IN]->(c:Conversation)
            OPTIONAL MATCH (w)-[:WROTE]->(m)
            OPTIONAL MATCH (m)-[:SUPERSEDES]->(prev:Memory)
            OPTIONAL MATCH (nxt:Memory)-[:SUPERSEDES]->(m)
            OPTIONAL MATCH (m)-[:ABOUT|MENTIONS]->(e:Entity)
            OPTIONAL MATCH (reader:Agent)-[rd:READ]->(m)
            RETURN m.content AS content, m.status AS status, m.version AS version, m.scopeKey AS scopeKey,
                   msg.id AS messageId, msg.snippet AS messageSnippet, msg.role AS messageRole,
                   c.id AS conversationId, c.title AS conversationTitle,
                   collect(DISTINCT [labels(w)[0], w.id, w.name]) AS writers,
                   collect(DISTINCT [prev.id, prev.content, prev.status]) AS previous,
                   collect(DISTINCT [nxt.id, nxt.content, nxt.status]) AS next,
                   collect(DISTINCT [e.key, e.name, e.kind]) AS entities,
                   collect(DISTINCT [reader.id, reader.name, rd.count, rd.lastAt]) AS readers""";
    static final String VIEW_NODES = """
            MATCH (n)
            WHERE NOT n:Alert AND ($label IS NULL OR labels(n)[0] = $label)
            RETURN labels(n)[0] AS label, coalesce(n.id, n.key) AS id,
                   coalesce(n.name, n.title, n.content, n.summary, n.key, n.id) AS name, n.status AS status,
                   n.scopeKey AS scopeKey, n.type AS type
            LIMIT $limit""";
    static final String VIEW_EDGES = """
            MATCH (a)-[r]->(b)
            WHERE NOT a:Alert AND NOT b:Alert
            RETURN labels(a)[0] AS fromLabel, coalesce(a.id, a.key) AS fromId, type(r) AS type, r.predicate AS predicate,
                   r.status AS status, labels(b)[0] AS toLabel, coalesce(b.id, b.key) AS toId,
                   a.scopeKey AS fromScope, b.scopeKey AS toScope
            LIMIT $limit""";
    static final String NEIGHBOURHOOD = """
            MATCH (n) WHERE n.id = $id OR n.key = $id
            MATCH (n)-[r]-(o)
            WHERE NOT o:Alert
            RETURN labels(n)[0] AS fromLabel, coalesce(n.id, n.key) AS fromId, n.scopeKey AS fromScope,
                   type(r) AS type, r.predicate AS predicate, r.status AS status, startNode(r) = n AS outgoing,
                   labels(o)[0] AS label, coalesce(o.id, o.key) AS id,
                   coalesce(o.name, o.title, o.content, o.summary, o.key, o.id) AS name, o.status AS nodeStatus,
                   o.scopeKey AS scopeKey, o.type AS memoryType
            LIMIT 200""";
    static final String COUNTS = """
            MATCH (n) WHERE NOT n:Alert
            RETURN labels(n)[0] AS label, count(n) AS n""";
    static final String EDGE_COUNTS = """
            MATCH (a)-[r]->(b) WHERE NOT a:Alert AND NOT b:Alert
            RETURN type(r) AS type, count(r) AS n""";

    public record Explanation(String memoryId, String from, GraphPath path, Map<String, Object> provenance) {
    }

    public record Node(String key, String label, String id, String name, String status, String type) {
    }

    public record Edge(String from, String to, String type, String predicate, String status) {
    }

    public record GraphView(List<Node> nodes, List<Edge> edges, Map<String, Long> counts) {
    }

    private static final Set<String> LABELS = Set.of("Memory", "Entity", "Agent", "User", "Project", "Conversation", "Message",
            "Task", "Handoff", "Run", "Step");

    private final GraphClient graph;
    private final TenantGraphs graphs;
    private final MemoryService memories;
    private final GraphProjector projector;

    public GraphExplainService(GraphClient graph, TenantGraphs graphs, MemoryService memories, GraphProjector projector) {
        this.graph = graph;
        this.graphs = graphs;
        this.memories = memories;
        this.projector = projector;
    }

    public Explanation explain(Actor actor, UUID memoryId, String from) {
        // PostgreSQL decides visibility (RLS + personal scope); a hidden or foreign memory is a 404 here too.
        memories.get(actor, memoryId);
        projector.catchUp(actor.tenantId());
        String g = graphs.of(actor);
        GraphPath path = null;
        if (from != null && !from.isBlank()) {
            int colon = from.indexOf(':');
            if (colon < 1) {
                throw ApiException.badRequest("from must be task:<id>, agent:<id> or entity:<key>");
            }
            String kind = from.substring(0, colon);
            String ref = from.substring(colon + 1);
            String cypher = switch (kind) {
                case "task" -> PATH_FROM_TASK;
                case "agent" -> PATH_FROM_AGENT;
                case "entity" -> PATH_FROM_ENTITY;
                default -> throw ApiException.badRequest("from must be task:<id>, agent:<id> or entity:<key>");
            };
            if (kind.equals("entity")) {
                ref = GraphKeys.entityKey(ref);
            } else {
                ref = UUID.fromString(ref).toString();
            }
            List<Map<String, Object>> rows = graph.read(g, "explain.path." + kind, cypher,
                    Map.of("from", ref, "memoryId", memoryId.toString()));
            if (!rows.isEmpty()) {
                path = GraphPath.of(rows.get(0).get("nodes"), rows.get(0).get("rels"));
            }
        }
        List<Map<String, Object>> prov = graph.read(g, "explain.provenance", PROVENANCE, Map.of("memoryId", memoryId.toString()));
        Map<String, Object> provenance = prov.isEmpty() ? Map.of() : provenance(prov.get(0));
        return new Explanation(memoryId.toString(), from, path, provenance);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> provenance(Map<String, Object> r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", r.get("content"));
        out.put("status", r.get("status"));
        out.put("version", r.get("version"));
        if (r.get("messageId") != null) {
            out.put("message", Map.of("id", r.get("messageId"), "role", String.valueOf(r.get("messageRole")),
                    "snippet", String.valueOf(r.get("messageSnippet"))));
            out.put("conversation", Map.of("id", r.get("conversationId"), "title", String.valueOf(r.get("conversationTitle"))));
        }
        out.put("writtenBy", triples((List<Object>) r.get("writers"), "label", "id", "name"));
        out.put("supersedes", triples((List<Object>) r.get("previous"), "id", "content", "status"));
        out.put("supersededBy", triples((List<Object>) r.get("next"), "id", "content", "status"));
        out.put("entities", triples((List<Object>) r.get("entities"), "key", "name", "kind"));
        out.put("readBy", triples((List<Object>) r.get("readers"), "id", "name", "count", "lastAt"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> triples(List<Object> rows, String... names) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object o : rows) {
            List<Object> l = (List<Object>) o;
            if (l.stream().allMatch(java.util.Objects::isNull) || l.get(names[0].equals("label") ? 1 : 0) == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < names.length && i < l.size(); i++) {
                m.put(names[i], l.get(i));
            }
            out.add(m);
        }
        return out;
    }

    public GraphView view(Actor actor, String label, int limit) {
        if (label != null && !LABELS.contains(label)) {
            throw ApiException.badRequest("Unknown label " + label);
        }
        projector.catchUp(actor.tenantId());
        String g = graphs.of(actor);
        Map<String, Object> p = new HashMap<>();
        p.put("label", label);
        p.put("limit", limit);
        List<Node> nodes = new ArrayList<>();
        Set<String> keys = new java.util.HashSet<>();
        for (Map<String, Object> r : graph.read(g, "view.nodes", VIEW_NODES, p)) {
            if (!GraphAlerts.visibleScope(actor, (String) r.get("scopeKey"))) {
                continue;
            }
            Node n = node(r.get("label"), r.get("id"), r.get("name"), r.get("status"), r.get("type"));
            nodes.add(n);
            keys.add(n.key());
        }
        List<Edge> edges = new ArrayList<>();
        for (Map<String, Object> r : graph.read(g, "view.edges", VIEW_EDGES, Map.of("limit", limit * 4))) {
            String a = r.get("fromLabel") + ":" + r.get("fromId");
            String b = r.get("toLabel") + ":" + r.get("toId");
            if (keys.contains(a) && keys.contains(b)) {
                edges.add(new Edge(a, b, (String) r.get("type"), (String) r.get("predicate"), (String) r.get("status")));
            }
        }
        return new GraphView(nodes, edges, counts(g));
    }

    public GraphView neighbourhood(Actor actor, String id) {
        String g = graphs.of(actor);
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        for (Map<String, Object> r : graph.read(g, "view.neighbourhood", NEIGHBOURHOOD, Map.of("id", id))) {
            if (!GraphAlerts.visibleScope(actor, (String) r.get("scopeKey"))
                    || !GraphAlerts.visibleScope(actor, (String) r.get("fromScope"))) {
                continue;
            }
            Node n = node(r.get("label"), r.get("id"), r.get("name"), r.get("nodeStatus"), r.get("memoryType"));
            nodes.add(n);
            String self = r.get("fromLabel") + ":" + r.get("fromId");
            boolean out = Boolean.TRUE.equals(r.get("outgoing"));
            edges.add(new Edge(out ? self : n.key(), out ? n.key() : self, (String) r.get("type"), (String) r.get("predicate"),
                    (String) r.get("status")));
        }
        return new GraphView(nodes, edges, Map.of());
    }

    public Map<String, Long> counts(String g) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map<String, Object> r : graph.read(g, "view.counts", COUNTS, Map.of())) {
            out.put(String.valueOf(r.get("label")), ((Number) r.get("n")).longValue());
        }
        for (Map<String, Object> r : graph.read(g, "view.edge-counts", EDGE_COUNTS, Map.of())) {
            out.put("rel:" + r.get("type"), ((Number) r.get("n")).longValue());
        }
        return out;
    }

    private static Node node(Object label, Object id, Object name, Object status, Object type) {
        String n = name == null ? null : String.valueOf(name);
        if (n != null && n.length() > 160) {
            n = n.substring(0, 157) + "...";
        }
        return new Node(label + ":" + id, String.valueOf(label), String.valueOf(id), n, status == null ? null : String.valueOf(status),
                type == null ? null : String.valueOf(type));
    }
}
