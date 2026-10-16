package com.memora.task;

import com.memora.common.BaseEntity;
import com.memora.security.ActorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only task history. */
@Entity
@Table(name = "task_events")
public class TaskEvent extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private ActorType actorType;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private TaskStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", updatable = false)
    private TaskStatus toStatus;

    @Column(name = "task_version", updatable = false)
    private Long taskVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected TaskEvent() {
    }

    TaskEvent(UUID tenantId, UUID taskId, String eventType, ActorType actorType, UUID actorId, TaskStatus fromStatus,
              TaskStatus toStatus, Long taskVersion, Map<String, Object> payload) {
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.eventType = eventType;
        this.actorType = actorType;
        this.actorId = actorId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.taskVersion = taskVersion;
        this.payload = payload == null ? new HashMap<>() : new HashMap<>(payload);
    }

    public UUID getTaskId() { return taskId; }
    public String getEventType() { return eventType; }
    public ActorType getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public TaskStatus getFromStatus() { return fromStatus; }
    public TaskStatus getToStatus() { return toStatus; }
    public Long getTaskVersion() { return taskVersion; }
    public Map<String, Object> getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
}
