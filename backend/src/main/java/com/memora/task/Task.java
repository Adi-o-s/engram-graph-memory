package com.memora.task;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A shared, versioned unit of work. Every write must name the version it read (optimistic locking). */
@Entity
@Table(name = "tasks")
public class Task extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status = TaskStatus.TODO;

    @Column(nullable = false)
    private int priority = 3;

    @Column(name = "assignee_agent_id")
    private UUID assigneeAgentId;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_by_agent_id", updatable = false)
    private UUID createdByAgentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> state = new HashMap<>();

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Task() {
    }

    Task(UUID tenantId, UUID projectId, String title, String description, int priority, UUID assigneeAgentId,
         UUID createdByUserId, UUID createdByAgentId, Map<String, Object> state, String idempotencyKey) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.assigneeAgentId = assigneeAgentId;
        this.createdByUserId = createdByUserId;
        this.createdByAgentId = createdByAgentId;
        this.state = state == null ? new HashMap<>() : new HashMap<>(state);
        this.idempotencyKey = idempotencyKey;
    }

    void setTitle(String title) { this.title = title; touch(); }
    void setDescription(String description) { this.description = description; touch(); }
    void setStatus(TaskStatus status) { this.status = status; touch(); }
    void setPriority(int priority) { this.priority = priority; touch(); }
    void setAssigneeAgentId(UUID assigneeAgentId) { this.assigneeAgentId = assigneeAgentId; touch(); }
    void setState(Map<String, Object> state) { this.state = new HashMap<>(state); touch(); }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getProjectId() { return projectId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TaskStatus getStatus() { return status; }
    public int getPriority() { return priority; }
    public UUID getAssigneeAgentId() { return assigneeAgentId; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public UUID getCreatedByAgentId() { return createdByAgentId; }
    public Map<String, Object> getState() { return state; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
