package com.memora.graph;

import com.memora.security.Actor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Neighbourhood, who-knows-what and impact analysis over the tenant graph (used by the agent graph tools). */
@Service
public class GraphInsights {

    private static final String VISIBLE_PATH = """
            all(n IN nodes(p) WHERE n.scopeKey IS NULL OR $seesAll OR NOT n.scopeKey STARTS WITH 'user:' OR n.scopeKey = $userScope)
              AND all(n IN nodes(p) WHERE n.status IS NULL OR NOT n:Memory OR n.status = 'CURRENT')
              AND all(r IN relationships(p) WHERE type(r) <> 'RELATES' OR r.status = 'CURRENT')""";
    private static final String NEIGHBOUR_RETURN = """
            WITH n, min(length(p)) AS hops
            RETURN labels(n)[0] AS label, coalesce(n.id, n.key) AS id,
                   coalesce(n.name, n.title, n.content, n.summary, n.key) AS name, hops
            ORDER BY hops, label
            LIMIT 60""";
    static final String NEIGHBOURS_1 = """
            MATCH (e:Entity {key: $key})
            MATCH p = (e)-[:ABOUT|MENTIONS|RELATES]-(n)
            WHERE NOT n:Alert AND
            """ + VISIBLE_PATH + "\n" + NEIGHBOUR_RETURN;
    static final String NEIGHBOURS_2 = """
            MATCH (e:Entity {key: $key})
            MATCH p = (e)-[:ABOUT|MENTIONS|RELATES*1..2]-(n)
            WHERE NOT n:Alert AND n <> e AND
            """ + VISIBLE_PATH + "\n" + NEIGHBOUR_RETURN;
    static final String WRITERS = """
            MATCH (e:Entity {key: $key})<-[:ABOUT|MENTIONS]-(m:Memory)<-[:WROTE]-(w)
            WHERE m.status = 'CURRENT' AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
            RETURN labels(w)[0] AS label, w.id AS id, w.name AS name, count(DISTINCT m) AS n""";
    static final String READERS = """
            MATCH (e:Entity {key: $key})<-[:ABOUT|MENTIONS]-(m:Memory)<-[rd:READ]-(r)
            WHERE m.status = 'CURRENT' AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
            RETURN labels(r)[0] AS label, r.id AS id, r.name AS name, count(DISTINCT m) AS n, sum(rd.count) AS reads""";
    static final String DEPENDENTS = """
            MATCH (dep:Entity)-[r:RELATES]->(e:Entity {key: $key})
            WHERE r.status = 'CURRENT'
            RETURN dep.key AS key, dep.name AS name, r.predicate AS predicate""";
    static final String IMPACT = """
            MATCH (e:Entity {key: $key})<-[:ABOUT|MENTIONS]-(m:Memory)
            WHERE m.status = 'CURRENT' AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
            OPTIONAL MATCH (m)-[:FOR_TASK]->(t:Task)
            OPTIONAL MATCH (h:Handoff)-[:CARRIES]->(m)
            OPTIONAL MATCH (h)-[:FOR]->(ht:Task)
            OPTIONAL MATCH (run:Run)-[:HAS_STEP]->(:Step)-[:USED|PRODUCED]->(m)
            RETURN collect(DISTINCT [m.id, m.content]) AS memories,
                   collect(DISTINCT [t.id, t.title, t.status]) + collect(DISTINCT [ht.id, ht.title, ht.status]) AS tasks,
                   collect(DISTINCT [h.id, h.status, h.summary]) AS handoffs,
                   collect(DISTINCT [run.id, run.name, run.status]) AS runs""";

    static final String RESOLVE = """
            MATCH (e:Entity) WHERE e.key IN $keys
            OPTIONAL MATCH (e)-[r]-()
            RETURN e.key AS key, count(r) AS degree
            ORDER BY degree DESC
            LIMIT 1""";

    private final GraphClient graph;
    private final TenantGraphs graphs;
    private final GraphProjector projector;

    public GraphInsights(GraphClient graph, TenantGraphs graphs, GraphProjector projector) {
        this.graph = graph;
        this.graphs = graphs;
        this.projector = projector;
    }

    private Map<String, Object> params(Actor actor, String entity) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", GraphKeys.entityKey(entity));
        p.put("seesAll", actor.seesAllUserData());
        p.put("userScope", "user:" + actor.userId());
        return p;
    }

    /** The best-connected entity among the terms of a text, if the graph knows any of them. */
    public java.util.Optional<String> resolveEntity(Actor actor, String text) {
        List<String> keys = GraphKeys.queryKeys(text);
        if (keys.isEmpty()) {
            return java.util.Optional.empty();
        }
        projector.catchUp(actor.tenantId());
        List<Map<String, Object>> rows = graph.read(graphs.of(actor), "tool.resolve-entity", RESOLVE, Map.of("keys", keys));
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(String.valueOf(rows.get(0).get("key")));
    }

    public Map<String, Object> neighbours(Actor actor, String entity, int depth) {
        projector.catchUp(actor.tenantId());
        Map<String, Object> p = params(actor, entity);
        List<Map<String, Object>> rows = graph.read(graphs.of(actor), "tool.neighbours." + (depth >= 2 ? 2 : 1),
                depth >= 2 ? NEIGHBOURS_2 : NEIGHBOURS_1, p);
        return Map.of("entity", p.get("key"), "depth", depth >= 2 ? 2 : 1, "neighbours", rows, "count", rows.size());
    }

    public Map<String, Object> whoKnows(Actor actor, String entity) {
        projector.catchUp(actor.tenantId());
        Map<String, Object> p = params(actor, entity);
        String g = graphs.of(actor);
        Map<String, Map<String, Object>> people = new LinkedHashMap<>();
        for (Map<String, Object> r : graph.read(g, "tool.who-knows.writers", WRITERS, p)) {
            person(people, r).put("wrote", ((Number) r.get("n")).longValue());
        }
        for (Map<String, Object> r : graph.read(g, "tool.who-knows.readers", READERS, p)) {
            Map<String, Object> m = person(people, r);
            m.put("read", ((Number) r.get("n")).longValue());
            m.put("reads", r.get("reads") instanceof Number n ? n.longValue() : 0L);
        }
        List<Map<String, Object>> ranked = new ArrayList<>(people.values());
        ranked.forEach(m -> m.put("score", 2 * (long) m.getOrDefault("wrote", 0L) + (long) m.getOrDefault("read", 0L)));
        ranked.sort(Comparator.comparingLong((Map<String, Object> m) -> (long) m.get("score")).reversed());
        return Map.of("entity", p.get("key"), "ranking", ranked, "count", ranked.size());
    }

    private static Map<String, Object> person(Map<String, Map<String, Object>> people, Map<String, Object> r) {
        return people.computeIfAbsent(r.get("label") + ":" + r.get("id"), k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", r.get("label"));
            m.put("id", r.get("id"));
            m.put("name", r.get("name"));
            return m;
        });
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> impact(Actor actor, String entity) {
        projector.catchUp(actor.tenantId());
        Map<String, Object> p = params(actor, entity);
        String g = graphs.of(actor);
        List<Map<String, Object>> dependents = graph.read(g, "tool.impact.dependents", DEPENDENTS, p);
        List<Map<String, Object>> rows = graph.read(g, "tool.impact", IMPACT, p);
        Map<String, Object> r = rows.isEmpty() ? Map.of() : rows.get(0);
        return Map.of("entity", p.get("key"), "dependents", dependents,
                "memories", named((List<Object>) r.get("memories"), "id", "content"),
                "tasks", named((List<Object>) r.get("tasks"), "id", "title", "status"),
                "handoffs", named((List<Object>) r.get("handoffs"), "id", "status", "summary"),
                "runs", named((List<Object>) r.get("runs"), "id", "name", "status"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> named(List<Object> rows, String... names) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        for (Object o : rows) {
            List<Object> l = (List<Object>) o;
            if (l.isEmpty() || l.get(0) == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < names.length && i < l.size(); i++) {
                m.put(names[i], l.get(i));
            }
            if (!out.contains(m)) {
                out.add(m);
            }
        }
        return out;
    }
}
