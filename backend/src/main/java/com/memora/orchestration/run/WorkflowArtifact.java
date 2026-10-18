package com.memora.orchestration.run;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An immutable, versioned output of a step (a document, a JSON contract, a report). */
@Entity
@Table(name = "workflow_artifacts")
public class WorkflowArtifact extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "step_run_id", updatable = false)
    private UUID stepRunId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private String content;

    @Column(nullable = false, updatable = false)
    private String checksum;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private int sizeBytes;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(name = "created_by_agent_id", updatable = false)
    private UUID createdByAgentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WorkflowArtifact() {
    }

    WorkflowArtifact(UUID tenantId, UUID runId, UUID stepRunId, UUID projectId, String name, String kind, String content,
                     String checksum, int sizeBytes, int version, UUID createdByAgentId) {
        this.tenantId = tenantId;
        this.runId = runId;
        this.stepRunId = stepRunId;
        this.projectId = projectId;
        this.name = name;
        this.kind = kind;
        this.content = content;
        this.checksum = checksum;
        this.sizeBytes = sizeBytes;
        this.version = version;
        this.createdByAgentId = createdByAgentId;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getRunId() { return runId; }
    public UUID getStepRunId() { return stepRunId; }
    public UUID getProjectId() { return projectId; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getContent() { return content; }
    public String getChecksum() { return checksum; }
    public int getSizeBytes() { return sizeBytes; }
    public int getVersion() { return version; }
    public UUID getCreatedByAgentId() { return createdByAgentId; }
    public Instant getCreatedAt() { return createdAt; }
}
