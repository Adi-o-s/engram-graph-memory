package com.memora.graph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.graph.GraphKeys.EntityRef;
import java.sql.Array;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Projects PostgreSQL rows into a tenant's FalkorDB graph. Each kind of entity is re-read from PostgreSQL
 * (its current state) and written with {@code UNWIND $rows ... MERGE}, so projecting the same entity twice,
 * or replaying an outbox row, leaves the graph unchanged. The caller must run in system database scope;
 * every SQL statement here filters by the tenant explicitly.
 */
@Component
public class GraphProjection {

    // ---- Memory ---------------------------------------------------------------------------------------
    static final String MEMORY_NODE = """
            UNWIND $rows AS r
            MERGE (m:Memory {id: r.id})
            ON CREATE SET m.createdAt = r.createdAt
            SET m.type = r.type, m.category = r.category, m.content = r.content, m.status = r.status,
                m.version = r.version, m.importance = r.importance, m.confidence = r.confidence,
                m.factKey = r.factKey, m.scopeKey = r.scopeKey, m.projectId = r.projectId, m.subject = r.subject,
                m.predicate = r.predicate, m.value = r.value, m.expiresAt = r.expiresAt, m.updatedAt = r.updatedAt
            RETURN count(m) AS n""";
    static final String MEMORY_ABOUT = """
            UNWIND $rows AS r
            WITH r WHERE r.aboutKey IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (e:Entity {key: r.aboutKey})
            ON CREATE SET e.name = r.aboutName, e.kind = r.aboutKind, e.createdAt = r.createdAt
            MERGE (m)-[:ABOUT]->(e)
            RETURN count(e) AS n""";
    static final String MEMORY_MENTIONS = """
            UNWIND $mentions AS x
            MATCH (m:Memory {id: x.memoryId})
            MERGE (e:Entity {key: x.key})
            ON CREATE SET e.name = x.name, e.kind = x.kind, e.createdAt = x.createdAt
            MERGE (m)-[:MENTIONS]->(e)
            RETURN count(e) AS n""";
    static final String MEMORY_RELATES = """
            UNWIND $rows AS r
            WITH r WHERE r.relates
            MATCH (a:Entity {key: r.aboutKey}), (b:Entity {key: r.valueKey})
            MERGE (a)-[x:RELATES {memoryId: r.id}]->(b)
            SET x.predicate = r.predicate, x.status = r.status
            RETURN count(x) AS n""";
    static final String MEMORY_SUPERSEDES = """
            UNWIND $rows AS r
            WITH r WHERE r.supersedesId IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (o:Memory {id: r.supersedesId})
            MERGE (m)-[:SUPERSEDES]->(o)
            RETURN count(o) AS n""";
    static final String MEMORY_PROVENANCE = """
            UNWIND $rows AS r
            WITH r WHERE r.messageId IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (msg:Message {id: r.messageId})
            MERGE (m)-[:EXTRACTED_FROM]->(msg)
            RETURN count(msg) AS n""";
    static final String MEMORY_PROJECT = """
            UNWIND $rows AS r
            WITH r WHERE r.projectId IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (p:Project {id: r.projectId})
            MERGE (m)-[:IN_PROJECT]->(p)
            RETURN count(p) AS n""";
    static final String MEMORY_TASK = """
            UNWIND $rows AS r
            WITH r WHERE r.taskId IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (t:Task {id: r.taskId})
            MERGE (m)-[:FOR_TASK]->(t)
            RETURN count(t) AS n""";
    static final String MEMORY_WRITTEN_BY_AGENT = """
            UNWIND $rows AS r
            WITH r WHERE r.agentId IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (a:Agent {id: r.agentId})
            MERGE (a)-[:WROTE]->(m)
            RETURN count(a) AS n""";
    static final String MEMORY_WRITTEN_BY_USER = """
            UNWIND $rows AS r
            WITH r WHERE r.userId IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (u:User {id: r.userId})
            SET u.name = r.userName
            MERGE (u)-[:WROTE]->(m)
            RETURN count(u) AS n""";
    static final String MEMORY_PRODUCED_BY_STEP = """
            UNWIND $rows AS r
            WITH r WHERE r.stepId IS NOT NULL
            MATCH (m:Memory {id: r.id})
            MERGE (s:Step {id: r.stepId})
            MERGE (s)-[:PRODUCED]->(m)
            RETURN count(s) AS n""";

    // ---- Coordination ---------------------------------------------------------------------------------
    static final String PROJECT_NODE = """
            UNWIND $rows AS r
            MERGE (p:Project {id: r.id})
            ON CREATE SET p.createdAt = r.createdAt
            SET p.name = r.name, p.slug = r.slug
            RETURN count(p) AS n""";
    static final String AGENT_NODE = """
            UNWIND $rows AS r
            MERGE (a:Agent {id: r.id})
            ON CREATE SET a.createdAt = r.createdAt
            SET a.name = r.name, a.role = r.role
            RETURN count(a) AS n""";
    static final String CONVERSATION_NODE = """
            UNWIND $rows AS r
            MERGE (c:Conversation {id: r.id})
            ON CREATE SET c.createdAt = r.createdAt
            SET c.title = r.title
            WITH c, r WHERE r.projectId IS NOT NULL
            MERGE (p:Project {id: r.projectId})
            MERGE (c)-[:IN_PROJECT]->(p)
            RETURN count(c) AS n""";
    static final String MESSAGE_NODE = """
            UNWIND $rows AS r
            MERGE (msg:Message {id: r.id})
            ON CREATE SET msg.createdAt = r.createdAt
            SET msg.role = r.role, msg.seq = r.seq, msg.snippet = r.snippet
            MERGE (c:Conversation {id: r.conversationId})
            MERGE (msg)-[:IN]->(c)
            RETURN count(msg) AS n""";
    static final String TASK_NODE = """
            UNWIND $rows AS r
            MERGE (t:Task {id: r.id})
            ON CREATE SET t.createdAt = r.createdAt
            SET t.title = r.title, t.status = r.status, t.version = r.version, t.priority = r.priority
            WITH t, r
            OPTIONAL MATCH (t)-[old:ASSIGNED_TO]->(:Agent)
            DELETE old
            RETURN count(DISTINCT t) AS n""";
    static final String TASK_ASSIGNEE = """
            UNWIND $rows AS r
            WITH r WHERE r.assigneeId IS NOT NULL
            MATCH (t:Task {id: r.id})
            MERGE (a:Agent {id: r.assigneeId})
            MERGE (t)-[:ASSIGNED_TO]->(a)
            RETURN count(a) AS n""";
    static final String TASK_PROJECT = """
            UNWIND $rows AS r
            WITH r WHERE r.projectId IS NOT NULL
            MATCH (t:Task {id: r.id})
            MERGE (p:Project {id: r.projectId})
            MERGE (t)-[:IN_PROJECT]->(p)
            RETURN count(p) AS n""";
    static final String HANDOFF_NODE = """
            UNWIND $rows AS r
            MERGE (h:Handoff {id: r.id})
            ON CREATE SET h.createdAt = r.createdAt
            SET h.status = r.status, h.summary = r.summary, h.nextAction = r.nextAction
            MERGE (f:Agent {id: r.fromId})
            MERGE (to:Agent {id: r.toId})
            MERGE (t:Task {id: r.taskId})
            MERGE (h)-[:FROM]->(f)
            MERGE (h)-[:TO]->(to)
            MERGE (h)-[:FOR]->(t)
            RETURN count(h) AS n""";
    static final String HANDOFF_CARRIES = """
            UNWIND $carries AS x
            MATCH (h:Handoff {id: x.handoffId}), (m:Memory {id: x.memoryId})
            MERGE (h)-[:CARRIES]->(m)
            RETURN count(m) AS n""";
    static final String RUN_NODE = """
            UNWIND $rows AS r
            MERGE (run:Run {id: r.id})
            ON CREATE SET run.createdAt = r.createdAt
            SET run.status = r.status, run.name = r.name
            WITH run, r
            MERGE (p:Project {id: r.projectId})
            MERGE (run)-[:IN_PROJECT]->(p)
            RETURN count(run) AS n""";
    static final String RUN_TASK = """
            UNWIND $rows AS r
            WITH r WHERE r.taskId IS NOT NULL
            MATCH (run:Run {id: r.id})
            MERGE (t:Task {id: r.taskId})
            MERGE (run)-[:FOR_TASK]->(t)
            RETURN count(t) AS n""";
    static final String STEP_NODE = """
            UNWIND $rows AS r
            MERGE (s:Step {id: r.id})
            ON CREATE SET s.createdAt = r.createdAt
            SET s.name = r.name, s.status = r.status, s.stepKey = r.stepKey
            MERGE (run:Run {id: r.runId})
            MERGE (run)-[:HAS_STEP]->(s)
            WITH s, r WHERE r.agentId IS NOT NULL
            MERGE (a:Agent {id: r.agentId})
            MERGE (s)-[:EXECUTED_BY]->(a)
            RETURN count(s) AS n""";

    // ---- Reads (who has seen which memory) -------------------------------------------------------------
    static final String AGENT_READ = """
            MERGE (a:Agent {id: $readerId})
            WITH a
            UNWIND $memoryIds AS mid
            MATCH (m:Memory {id: mid})
            MERGE (a)-[r:READ]->(m)
            ON CREATE SET r.count = 0, r.firstAt = $at
            SET r.count = r.count + 1, r.lastAt = $at
            RETURN count(r) AS n""";
    static final String USER_READ = """
            MERGE (u:User {id: $readerId})
            WITH u
            UNWIND $memoryIds AS mid
            MATCH (m:Memory {id: mid})
            MERGE (u)-[r:READ]->(m)
            ON CREATE SET r.count = 0, r.firstAt = $at
            SET r.count = r.count + 1, r.lastAt = $at
            RETURN count(r) AS n""";
    static final String STEP_USED = """
            MERGE (s:Step {id: $stepId})
            WITH s
            UNWIND $memoryIds AS mid
            MATCH (m:Memory {id: mid})
            MERGE (s)-[:USED]->(m)
            RETURN count(m) AS n""";

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final GraphClient graph;
    private final ObjectMapper objectMapper;

    public GraphProjection(JdbcTemplate jdbc, GraphClient graph, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.objectMapper = objectMapper;
    }

    /** Projects the current state of the given entities. Returns the ids of memories that are no longer CURRENT. */
    public List<String> project(String g, UUID tenantId, String kind, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        UUID[] arr = ids.toArray(UUID[]::new);
        return switch (kind) {
            case "MEMORY" -> memories(g, tenantId, arr);
            case "PROJECT" -> simple(g, "project.node", PROJECT_NODE, jdbc.queryForList(
                    "SELECT id, name, slug, created_at FROM projects WHERE tenant_id = ? AND id = ANY(?)", tenantId, arr),
                    r -> row("id", r.get("id"), "name", r.get("name"), "slug", r.get("slug"), "createdAt", r.get("created_at")));
            case "AGENT" -> simple(g, "agent.node", AGENT_NODE, jdbc.queryForList(
                    "SELECT id, name, role, created_at FROM agents WHERE tenant_id = ? AND id = ANY(?)", tenantId, arr),
                    r -> row("id", r.get("id"), "name", r.get("name"), "role", r.get("role"), "createdAt", r.get("created_at")));
            case "CONVERSATION" -> simple(g, "conversation.node", CONVERSATION_NODE, jdbc.queryForList(
                    "SELECT id, title, project_id, created_at FROM conversations WHERE tenant_id = ? AND id = ANY(?)", tenantId, arr),
                    r -> row("id", r.get("id"), "title", r.get("title"), "projectId", r.get("project_id"), "createdAt", r.get("created_at")));
            case "MESSAGE" -> simple(g, "message.node", MESSAGE_NODE, jdbc.queryForList(
                    "SELECT id, conversation_id, role, seq, left(content, 200) AS snippet, created_at FROM messages "
                            + "WHERE tenant_id = ? AND id = ANY(?)", tenantId, arr),
                    r -> row("id", r.get("id"), "conversationId", r.get("conversation_id"), "role", r.get("role"),
                            "seq", r.get("seq"), "snippet", r.get("snippet"), "createdAt", r.get("created_at")));
            case "TASK" -> tasks(g, tenantId, arr);
            case "HANDOFF" -> handoffs(g, tenantId, arr);
            case "RUN" -> runs(g, tenantId, arr);
            case "STEP" -> simple(g, "step.node", STEP_NODE, jdbc.queryForList("""
                            SELECT s.id, s.run_id, s.name, s.status, s.step_key, s.created_at,
                                   r.agents -> s.agent_key ->> 'agentId' AS agent_id
                            FROM workflow_step_runs s JOIN workflow_runs r ON r.tenant_id = s.tenant_id AND r.id = s.run_id
                            WHERE s.tenant_id = ? AND s.id = ANY(?)""", tenantId, arr),
                    r -> row("id", r.get("id"), "runId", r.get("run_id"), "name", r.get("name"), "status", r.get("status"),
                            "stepKey", r.get("step_key"), "agentId", r.get("agent_id"), "createdAt", r.get("created_at")));
            default -> List.of();
        };
    }

    /** Applies one READ outbox payload: an agent or user (and optionally a workflow step) read these memories. */
    public void projectRead(String g, Map<String, Object> payload) {
        Object memoryIds = payload.get("memoryIds");
        if (!(memoryIds instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        List<String> ids = list.stream().map(String::valueOf).toList();
        Object at = payload.getOrDefault("at", System.currentTimeMillis());
        if (payload.get("agentId") != null) {
            graph.write(g, "read.agent", AGENT_READ, Map.of("readerId", String.valueOf(payload.get("agentId")), "memoryIds", ids, "at", at));
        } else if (payload.get("userId") != null) {
            graph.write(g, "read.user", USER_READ, Map.of("readerId", String.valueOf(payload.get("userId")), "memoryIds", ids, "at", at));
        }
        if (payload.get("stepRunId") != null) {
            graph.write(g, "read.step", STEP_USED, Map.of("stepId", String.valueOf(payload.get("stepRunId")), "memoryIds", ids));
        }
    }

    public Map<String, Object> parse(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, MAP);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<String> memories(String g, UUID tenantId, UUID[] ids) {
        List<Map<String, Object>> sql = jdbc.queryForList("""
                SELECT m.id, m.memory_type, m.category, m.content, m.status, m.version, m.importance, m.confidence,
                       m.fact_key, m.scope_key, m.project_id, m.task_id, m.subject, m.predicate, m.object_value,
                       m.supersedes_id, m.source_message_id, m.created_by_user_id, m.created_by_agent_id, m.agent_id,
                       m.expires_at, m.created_at, m.updated_at, u.display_name, p.name AS project_name,
                       (SELECT s.id FROM workflow_step_runs s
                         WHERE s.tenant_id = m.tenant_id AND s.run_id = m.workflow_run_id
                           AND s.step_key = m.metadata ->> 'stepKey'
                           AND s.iteration::text = coalesce(m.metadata ->> 'iteration', '1') LIMIT 1) AS step_id
                FROM memories m
                LEFT JOIN users u ON u.id = m.created_by_user_id
                LEFT JOIN projects p ON p.tenant_id = m.tenant_id AND p.id = m.project_id
                WHERE m.tenant_id = ? AND m.id = ANY(?)""", tenantId, ids);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> mentions = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map<String, Object> r : sql) {
            String id = str(r.get("id"));
            String scopeKey = (String) r.get("scope_key");
            String subject = (String) r.get("subject");
            String value = (String) r.get("object_value");
            EntityRef about = aboutEntity(subject, r);
            EntityRef valueEntity = value == null ? null : GraphKeys.entity(value);
            boolean personal = scopeKey.startsWith("user:");
            Map<String, Object> row = row("id", id, "type", r.get("memory_type"), "category", r.get("category"),
                    "content", r.get("content"), "status", r.get("status"), "version", r.get("version"),
                    "importance", r.get("importance"), "confidence", r.get("confidence"), "factKey", r.get("fact_key"),
                    "scopeKey", scopeKey, "projectId", r.get("project_id"), "taskId", r.get("task_id"),
                    "subject", subject, "predicate", r.get("predicate"), "value", value,
                    "supersedesId", r.get("supersedes_id"), "messageId", r.get("source_message_id"),
                    "userId", r.get("created_by_user_id"), "userName", r.get("display_name"),
                    "agentId", r.get("created_by_agent_id") != null ? r.get("created_by_agent_id") : r.get("agent_id"),
                    "stepId", r.get("step_id"), "expiresAt", r.get("expires_at"), "createdAt", r.get("created_at"),
                    "updatedAt", r.get("updated_at"),
                    "aboutKey", about == null ? null : about.key(), "aboutName", about == null ? null : about.name(),
                    "aboutKind", about == null ? null : about.kind(),
                    "valueKey", valueEntity == null ? null : valueEntity.key());
            // Personal memories never become shared fact edges between entities.
            row.put("relates", about != null && valueEntity != null && !personal);
            rows.add(row);
            Map<String, EntityRef> mentioned = new LinkedHashMap<>();
            if (valueEntity != null) {
                mentioned.put(valueEntity.key(), valueEntity);
            }
            for (EntityRef e : GraphKeys.mentions((String) r.get("content"))) {
                mentioned.putIfAbsent(e.key(), e);
            }
            if (about != null) {
                mentioned.remove(about.key());
            }
            for (EntityRef e : mentioned.values()) {
                mentions.add(row("memoryId", id, "key", e.key(), "name", e.name(), "kind", e.kind(), "createdAt", r.get("created_at")));
            }
            if (!"CURRENT".equals(r.get("status"))) {
                changed.add(id);
            }
        }
        Map<String, Object> params = Map.of("rows", rows);
        graph.write(g, "memory.node", MEMORY_NODE, params);
        graph.write(g, "memory.about", MEMORY_ABOUT, params);
        if (!mentions.isEmpty()) {
            graph.write(g, "memory.mentions", MEMORY_MENTIONS, Map.of("mentions", mentions));
        }
        graph.write(g, "memory.relates", MEMORY_RELATES, params);
        graph.write(g, "memory.supersedes", MEMORY_SUPERSEDES, params);
        graph.write(g, "memory.provenance", MEMORY_PROVENANCE, params);
        graph.write(g, "memory.project", MEMORY_PROJECT, params);
        graph.write(g, "memory.task", MEMORY_TASK, params);
        graph.write(g, "memory.wrote.agent", MEMORY_WRITTEN_BY_AGENT, params);
        graph.write(g, "memory.wrote.user", MEMORY_WRITTEN_BY_USER, params);
        graph.write(g, "memory.produced", MEMORY_PRODUCED_BY_STEP, params);
        return changed;
    }

    private static EntityRef aboutEntity(String subject, Map<String, Object> r) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        String s = subject.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("user")) {
            return null;
        }
        if (s.equals("project")) {
            return r.get("project_id") == null ? new EntityRef("tenant", "Tenant", "tenant")
                    : new EntityRef("project:" + r.get("project_id"), String.valueOf(r.get("project_name")), "project");
        }
        return GraphKeys.entity(subject);
    }

    private List<String> tasks(String g, UUID tenantId, UUID[] ids) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, status, version, priority, project_id, assignee_agent_id, created_at FROM tasks "
                        + "WHERE tenant_id = ? AND id = ANY(?)", tenantId, ids).stream()
                .map(r -> row("id", r.get("id"), "title", r.get("title"), "status", r.get("status"), "version", r.get("version"),
                        "priority", r.get("priority"), "projectId", r.get("project_id"), "assigneeId", r.get("assignee_agent_id"),
                        "createdAt", r.get("created_at"))).toList();
        Map<String, Object> params = Map.of("rows", rows);
        graph.write(g, "task.node", TASK_NODE, params);
        graph.write(g, "task.assignee", TASK_ASSIGNEE, params);
        graph.write(g, "task.project", TASK_PROJECT, params);
        return List.of();
    }

    private List<String> handoffs(String g, UUID tenantId, UUID[] ids) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> carries = new ArrayList<>();
        jdbc.query("SELECT id, task_id, from_agent_id, to_agent_id, summary, next_action, status, memory_refs, created_at "
                + "FROM handoffs WHERE tenant_id = ? AND id = ANY(?)", rs -> {
            String id = str(rs.getObject("id"));
            rows.add(row("id", id, "taskId", rs.getObject("task_id"), "fromId", rs.getObject("from_agent_id"),
                    "toId", rs.getObject("to_agent_id"), "summary", rs.getString("summary"), "nextAction", rs.getString("next_action"),
                    "status", rs.getString("status"), "createdAt", rs.getTimestamp("created_at")));
            Array refs = rs.getArray("memory_refs");
            if (refs != null) {
                for (Object ref : (Object[]) refs.getArray()) {
                    carries.add(row("handoffId", id, "memoryId", ref));
                }
            }
        }, tenantId, ids);
        graph.write(g, "handoff.node", HANDOFF_NODE, Map.of("rows", rows));
        if (!carries.isEmpty()) {
            graph.write(g, "handoff.carries", HANDOFF_CARRIES, Map.of("carries", carries));
        }
        return List.of();
    }

    private List<String> runs(String g, UUID tenantId, UUID[] ids) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, status, project_id, task_id, created_at FROM workflow_runs WHERE tenant_id = ? AND id = ANY(?)",
                tenantId, ids).stream()
                .map(r -> row("id", r.get("id"), "name", r.get("name"), "status", r.get("status"), "projectId", r.get("project_id"),
                        "taskId", r.get("task_id"), "createdAt", r.get("created_at"))).toList();
        Map<String, Object> params = Map.of("rows", rows);
        graph.write(g, "run.node", RUN_NODE, params);
        graph.write(g, "run.task", RUN_TASK, params);
        return List.of();
    }

    private List<String> simple(String g, String name, String cypher, List<Map<String, Object>> sql,
                                java.util.function.Function<Map<String, Object>, Map<String, Object>> mapper) {
        if (!sql.isEmpty()) {
            graph.write(g, name, cypher, Map.of("rows", sql.stream().map(mapper).toList()));
        }
        return List.of();
    }

    /** Builds a parameter map with graph-safe values: UUIDs as strings, timestamps as epoch milliseconds. */
    static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], value(kv[i + 1]));
        }
        return m;
    }

    static Object value(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof UUID u) {
            return u.toString();
        }
        if (v instanceof Timestamp t) {
            return t.getTime();
        }
        if (v instanceof java.time.Instant i) {
            return i.toEpochMilli();
        }
        if (v instanceof java.time.OffsetDateTime o) {
            return o.toInstant().toEpochMilli();
        }
        if (v instanceof java.math.BigDecimal b) {
            return b.doubleValue();
        }
        return v;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
