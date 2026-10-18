package com.memora.orchestration.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Append-only execution history of a run. Joins the caller's transaction when there is one. */
@Component
public class EventLog {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public EventLog(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void append(UUID tenantId, UUID runId, UUID stepRunId, String type, String message, Map<String, Object> payload) {
        jdbc.update("""
                INSERT INTO workflow_events (id, tenant_id, run_id, step_run_id, event_type, message, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, runId, stepRunId, type,
                message == null ? null : message.length() > 4000 ? message.substring(0, 3997) + "..." : message,
                json(payload), Timestamp.from(Instant.now()));
    }

    public List<EventView> list(UUID tenantId, UUID runId, int limit) {
        return jdbc.query("""
                SELECT id, step_run_id, event_type, message, payload::text AS payload, created_at
                FROM workflow_events WHERE tenant_id = ? AND run_id = ? ORDER BY created_at, id LIMIT ?
                """, (rs, i) -> new EventView(rs.getObject("id", UUID.class), rs.getObject("step_run_id", UUID.class),
                rs.getString("event_type"), rs.getString("message"), parse(rs.getString("payload")),
                rs.getTimestamp("created_at").toInstant()), tenantId, runId, limit);
    }

    public record EventView(UUID id, UUID stepRunId, String type, String message, Map<String, Object> payload,
                            Instant createdAt) {
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
