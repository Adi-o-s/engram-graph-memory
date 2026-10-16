package com.memora.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.security.Actor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only audit trail. Writes join the caller's transaction, so an audit row exists exactly
 * when the change it describes was committed. Details hold IDs and short labels, never secrets.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(Actor actor, String action, String entityType, UUID entityId, Map<String, Object> details) {
        jdbc.update("""
                INSERT INTO audit_events (id, tenant_id, actor_type, actor_id, actor_name, action, entity_type,
                                          entity_id, details, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                UUID.randomUUID(), actor.tenantId(), actor.type().name(), actor.id(), actor.name(), action,
                entityType, entityId, toJson(details), MDC.get("correlationId"), Timestamp.from(Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> recent(Actor actor, String entityType, UUID entityId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, actor_type, actor_id, actor_name, action, entity_type, entity_id, details::text AS details,
                       correlation_id, created_at
                FROM audit_events WHERE tenant_id = ?""");
        List<Object> args = new java.util.ArrayList<>(List.of(actor.tenantId()));
        if (entityType != null) {
            sql.append(" AND entity_type = ?");
            args.add(entityType);
        }
        if (entityId != null) {
            sql.append(" AND entity_id = ?");
            args.add(entityId);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new AuditEventView(
                rs.getObject("id", UUID.class), rs.getString("actor_type"), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"), rs.getString("action"), rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class), fromJson(rs.getString("details")),
                rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public record AuditEventView(UUID id, String actorType, UUID actorId, String actorName, String action,
                                 String entityType, UUID entityId, Map<String, Object> details,
                                 String correlationId, Instant createdAt) {
    }
}
