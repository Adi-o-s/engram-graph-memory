package com.memora.graph;

import com.memora.common.Json;
import com.memora.security.Actor;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@code graph_outbox} table. Row changes are added by database triggers (V5 migration) in the writing
 * transaction; reads are added here, inside the reading request's transaction.
 */
@Repository
public class GraphOutbox {

    public record Row(long id, UUID tenantId, String kind, UUID entityId, String payload, int attempts) {
    }

    public record Lag(long pending, long failed, Double oldestPendingSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public GraphOutbox(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Records that the actor (agent or user), and optionally a workflow step, read these memories. */
    public void recordRead(Actor actor, Collection<UUID> memoryIds, UUID stepRunId) {
        if (memoryIds == null || memoryIds.isEmpty() || (actor.agentId() == null && actor.userId() == null)) {
            return;
        }
        Map<String, Object> payload = Json.map("agentId", actor.agentId() == null ? null : actor.agentId().toString(),
                "userId", actor.userId() == null ? null : actor.userId().toString(),
                "stepRunId", stepRunId == null ? null : stepRunId.toString(),
                "memoryIds", memoryIds.stream().map(UUID::toString).distinct().toList(),
                "at", System.currentTimeMillis());
        jdbc.update("INSERT INTO graph_outbox (tenant_id, kind, payload) VALUES (?, 'READ', CAST(? AS jsonb))",
                actor.tenantId(), json(payload));
    }

    /** Claims pending rows (optionally of one tenant). Must run inside a transaction; locks are held until it ends. */
    public List<Row> claim(UUID tenantId, int limit) {
        String sql = "SELECT id, tenant_id, kind, entity_id, payload::text AS payload, attempts FROM graph_outbox "
                + "WHERE status = 'PENDING' AND next_attempt_at <= clock_timestamp()"
                + (tenantId == null ? "" : " AND tenant_id = ?")
                + " ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED";
        Object[] args = tenantId == null ? new Object[]{limit} : new Object[]{tenantId, limit};
        return jdbc.query(sql, (rs, i) -> new Row(rs.getLong("id"), rs.getObject("tenant_id", UUID.class), rs.getString("kind"),
                rs.getObject("entity_id", UUID.class), rs.getString("payload"), rs.getInt("attempts")), args);
    }

    public void markDone(Collection<Long> ids) {
        if (!ids.isEmpty()) {
            jdbc.update("UPDATE graph_outbox SET status = 'DONE', processed_at = clock_timestamp(), last_error = NULL "
                    + "WHERE id = ANY(?)", (Object) ids.toArray(Long[]::new));
        }
    }

    /** Exponential backoff (1s, 2s, 4s ... capped at 5 min); FAILED after maxAttempts so one bad row cannot block others. */
    public void markFailed(long id, int attempts, int maxAttempts, String error) {
        long delayMs = Math.min(300_000L, 1000L << Math.min(18, attempts));
        jdbc.update("UPDATE graph_outbox SET attempts = ?, last_error = left(?, 1000), "
                        + "status = CASE WHEN ? >= ? THEN 'FAILED' ELSE 'PENDING' END, "
                        + "next_attempt_at = clock_timestamp() + (? * interval '1 millisecond') WHERE id = ?",
                attempts + 1, error, attempts + 1, maxAttempts, delayMs, id);
    }

    public long pendingFor(UUID tenantId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM graph_outbox WHERE tenant_id = ? AND status = 'PENDING'",
                Long.class, tenantId);
        return n == null ? 0 : n;
    }

    public Lag lag() {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'FAILED') AS failed,
                       extract(epoch FROM clock_timestamp() - min(created_at) FILTER (WHERE status = 'PENDING')) AS oldest
                FROM graph_outbox""", (rs, i) -> new Lag(rs.getLong("pending"), rs.getLong("failed"),
                rs.getObject("oldest") == null ? null : rs.getDouble("oldest")));
    }

    /** READ rows already applied for a tenant, oldest first: replayed by a rebuild (reads are not stored elsewhere). */
    public List<Row> appliedReads(UUID tenantId) {
        return jdbc.query("SELECT id, tenant_id, kind, entity_id, payload::text AS payload, attempts FROM graph_outbox "
                        + "WHERE tenant_id = ? AND kind = 'READ' AND status = 'DONE' ORDER BY id",
                (rs, i) -> new Row(rs.getLong("id"), rs.getObject("tenant_id", UUID.class), rs.getString("kind"),
                        rs.getObject("entity_id", UUID.class), rs.getString("payload"), rs.getInt("attempts")), tenantId);
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }
}
