package com.memora.orchestration.workflow;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_definitions")
public class WorkflowDefinition extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "wf_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WorkflowDefinition() {
    }

    WorkflowDefinition(UUID tenantId, String key, String name, String description, UUID projectId, UUID createdByUserId) {
        this.tenantId = tenantId;
        this.key = key;
        this.name = name;
        this.description = description;
        this.projectId = projectId;
        this.createdByUserId = createdByUserId;
    }

    void update(String name, String description, UUID projectId) {
        this.name = name;
        this.description = description;
        this.projectId = projectId;
        this.updatedAt = Instant.now();
    }

    void newVersion() {
        this.currentVersion++;
        this.updatedAt = Instant.now();
    }

    void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getProjectId() { return projectId; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getCurrentVersion() { return currentVersion; }
    public String getStatus() { return status; }
    public Long getLockVersion() { return lockVersion; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
