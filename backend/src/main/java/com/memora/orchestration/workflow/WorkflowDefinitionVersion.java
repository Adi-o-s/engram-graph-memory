package com.memora.orchestration.workflow;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An immutable, validated spec. Editing a workflow adds a version; runs reference the version they used. */
@Entity
@Table(name = "workflow_definition_versions")
public class WorkflowDefinitionVersion extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(nullable = false, updatable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> spec;

    @Column(name = "change_note", updatable = false)
    private String changeNote;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WorkflowDefinitionVersion() {
    }

    WorkflowDefinitionVersion(UUID tenantId, UUID workflowId, int version, Map<String, Object> spec, String changeNote,
                              UUID createdByUserId) {
        this.tenantId = tenantId;
        this.workflowId = workflowId;
        this.version = version;
        this.spec = spec;
        this.changeNote = changeNote;
        this.createdByUserId = createdByUserId;
    }

    public UUID getWorkflowId() { return workflowId; }
    public int getVersion() { return version; }
    public Map<String, Object> getSpec() { return spec; }
    public String getChangeNote() { return changeNote; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
