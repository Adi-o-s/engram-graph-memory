package com.memora.graph;

import com.memora.memory.MemoryType;
import com.memora.security.Actor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Graph-driven context retrieval over the caller's tenant graph.
 *
 * <ol>
 *   <li>Seeds: entities whose canonical key matches a query term ({@link GraphKeys#queryKeys}), full-text hits on
 *       {@code Memory.content}, and the task (its memories and the memories its handoffs carry).</li>
 *   <li>Expansion: up to 2 hops through {@code ABOUT|MENTIONS|RELATES|FOR_TASK|FOR|CARRIES}; only CURRENT memories,
 *       only CURRENT fact edges, and the same visibility rule as PostgreSQL search (another user's personal
 *       memories never match).</li>
 *   <li>Score: {@code 0.6^hops x importance/5 x confidence x (0.5 + 0.5 x e^(-ageDays/30))}; a memory reached
 *       by several paths keeps its best one. A full-text match (and a budget fill-up) is weighted like 3 extra
 *       hops, so a fact connected by edges outranks one that only shares a word with the query.</li>
 * </ol>
 * Every result carries the path that reached it. The caller re-checks the ids against PostgreSQL (RLS and
 * visibility) before anything is returned to a client.
 */
@Component
public class GraphContextRetriever {

    static final double HOP_DECAY = 0.6;
    static final double RECENCY_DAYS = 30.0;
    /** A full-text word match is weaker evidence than a graph edge: it scores like 3 extra hops. */
    static final double TEXT_WEIGHT = Math.pow(HOP_DECAY, 3);
    private static final Pattern TEXT_WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "what", "which",
            "how", "are", "was", "were", "our", "you", "your", "about", "into", "have", "has", "does", "use", "uses");

    /** Visibility and type filter shared by every retrieval query (parameters only, no user text in Cypher). */
    private static final String FILTER = """
            m.status = 'CURRENT' AND m.type = $type
              AND ($seesAll OR NOT m.scopeKey STARTS WITH 'user:' OR m.scopeKey = $userScope)
              AND ($projectId IS NULL OR m.projectId IS NULL OR m.projectId = $projectId)
              AND (m.expiresAt IS NULL OR m.expiresAt > $now)
              AND ($scopeKeys IS NULL OR m.scopeKey IN $scopeKeys)""";
    private static final String RETURN = """
            RETURN m.id AS id, length(p) AS hops, m.importance AS importance, m.confidence AS confidence,
                   m.createdAt AS createdAt,
                   [n IN nodes(p) | [labels(n)[0], coalesce(n.id, n.key), coalesce(n.name, n.title, n.content, n.summary, n.key)]] AS nodes,
                   [r IN relationships(p) | type(r)] AS rels
            LIMIT 300""";

    static final String BY_ENTITY = """
            MATCH (e:Entity) WHERE e.key IN $keys
            MATCH p = (e)-[:RELATES*0..1]-(:Entity)<-[:ABOUT|MENTIONS]-(m:Memory)
            WHERE all(r IN relationships(p) WHERE type(r) <> 'RELATES' OR r.status = 'CURRENT') AND
            """ + FILTER + "\n" + RETURN;
    static final String BY_TEXT = """
            CALL db.idx.fulltext.queryNodes('Memory', $text) YIELD node
            WITH node AS m
            MATCH p = (m)
            WHERE
            """ + FILTER + "\n" + RETURN;
    static final String BY_TEXT_NEIGHBOURS = """
            CALL db.idx.fulltext.queryNodes('Memory', $text) YIELD node
            WITH node AS hit LIMIT 20
            MATCH p = (hit)-[:ABOUT]->(:Entity)<-[:ABOUT|MENTIONS]-(m:Memory)
            WHERE hit.status = 'CURRENT' AND
            """ + FILTER + "\n" + RETURN;
    static final String BY_TASK = """
            MATCH (t:Task {id: $taskId})
            MATCH p = (t)<-[:FOR_TASK]-(m:Memory)
            WHERE
            """ + FILTER + "\n" + RETURN;
    static final String BY_TASK_HANDOFF = """
            MATCH (t:Task {id: $taskId})
            MATCH p = (t)<-[:FOR]-(:Handoff)-[:CARRIES]->(m:Memory)
            WHERE
            """ + FILTER + "\n" + RETURN;
    static final String MOST_IMPORTANT = """
            MATCH (m:Memory)
            WHERE
            """ + FILTER + """

            WITH m ORDER BY m.importance DESC, m.createdAt DESC LIMIT $limit
            MATCH p = (m)
            """ + RETURN;

    public record Candidate(UUID memoryId, int hops, double score, GraphPath path, boolean reached) {
    }

    private final GraphClient graph;
    private final TenantGraphs graphs;

    public GraphContextRetriever(GraphClient graph, TenantGraphs graphs) {
        this.graph = graph;
        this.graphs = graphs;
    }

    /**
     * Candidates for one memory type, best first: memories the graph reached from the query, the task and its
     * handoffs, then (if {@code fill}) the most important visible memories to fill the budget.
     */
    public List<Candidate> retrieve(Actor actor, MemoryType type, String query, UUID projectId, UUID taskId, int limit,
                                    boolean fill) {
        return retrieve(actor, type, query, projectId, taskId, null, limit, fill);
    }

    /** As above, restricted to memories in the given scope keys (a workflow agent's memory policy). */
    public List<Candidate> retrieve(Actor actor, MemoryType type, String query, UUID projectId, UUID taskId,
                                    List<String> scopeKeys, int limit, boolean fill) {
        String g = graphs.of(actor);
        Map<String, Object> base = new HashMap<>();
        base.put("type", type.name());
        base.put("seesAll", actor.seesAllUserData());
        base.put("userScope", "user:" + actor.userId());
        base.put("projectId", projectId == null ? null : projectId.toString());
        base.put("now", System.currentTimeMillis());
        base.put("scopeKeys", scopeKeys);
        Map<UUID, Candidate> best = new LinkedHashMap<>();
        if (taskId != null) {
            Map<String, Object> p = with(base, "taskId", taskId.toString());
            collect(best, graph.read(g, "context.task", BY_TASK, p), 1.0, true);
            collect(best, graph.read(g, "context.task-handoff", BY_TASK_HANDOFF, p), 1.0, true);
        }
        List<String> keys = GraphKeys.queryKeys(query);
        if (!keys.isEmpty()) {
            collect(best, graph.read(g, "context.entity", BY_ENTITY, with(base, "keys", keys)), 1.0, true);
        }
        String text = fullText(query);
        if (text != null) {
            Map<String, Object> p = with(base, "text", text);
            collect(best, graph.read(g, "context.text", BY_TEXT, p), TEXT_WEIGHT, true);
            collect(best, graph.read(g, "context.text-neighbours", BY_TEXT_NEIGHBOURS, p), TEXT_WEIGHT, true);
        }
        List<Candidate> reached = best.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed()).toList();
        List<Candidate> out = new ArrayList<>(reached.subList(0, Math.min(limit, reached.size())));
        if (fill && out.size() < limit) {
            Map<UUID, Candidate> rest = new LinkedHashMap<>();
            collect(rest, graph.read(g, "context.important", MOST_IMPORTANT, with(base, "limit", limit * 2)), TEXT_WEIGHT, false);
            for (Candidate c : rest.values()) {
                if (out.size() >= limit) {
                    break;
                }
                if (out.stream().noneMatch(o -> o.memoryId().equals(c.memoryId()))) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static void collect(Map<UUID, Candidate> best, List<Map<String, Object>> rows, double weight, boolean reached) {
        long now = System.currentTimeMillis();
        for (Map<String, Object> r : rows) {
            UUID id = UUID.fromString(String.valueOf(r.get("id")));
            int hops = ((Number) r.get("hops")).intValue();
            double importance = r.get("importance") instanceof Number n ? n.doubleValue() : 3;
            double confidence = r.get("confidence") instanceof Number n ? n.doubleValue() : 1;
            double ageDays = r.get("createdAt") instanceof Number n ? Math.max(0, (now - n.longValue()) / 86_400_000.0) : 0;
            double score = weight * Math.pow(HOP_DECAY, hops) * (importance / 5.0) * confidence
                    * (0.5 + 0.5 * Math.exp(-ageDays / RECENCY_DAYS));
            Candidate c = new Candidate(id, hops, Math.round(score * 1000.0) / 1000.0, GraphPath.of(r.get("nodes"), r.get("rels")), reached);
            Candidate prev = best.get(id);
            if (prev == null || c.score() > prev.score()) {
                best.put(id, c);
            }
        }
    }

    /** RediSearch syntax treats '-' and other symbols as operators: keep plain words only, OR-ed. */
    static String fullText(String query) {
        if (query == null) {
            return null;
        }
        List<String> words = new ArrayList<>();
        Matcher m = TEXT_WORD.matcher(query.toLowerCase(Locale.ROOT));
        while (m.find() && words.size() < 12) {
            if (!STOP.contains(m.group()) && !words.contains(m.group())) {
                words.add(m.group());
            }
        }
        return words.isEmpty() ? null : String.join("|", words);
    }

    private static Map<String, Object> with(Map<String, Object> base, String k, Object v) {
        Map<String, Object> m = new HashMap<>(base);
        m.put(k, v);
        return m;
    }
}
