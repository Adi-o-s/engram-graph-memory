package com.memora.orchestration.run;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A human approval gate in front of one step. */
@Entity
@Table(name = "workflow_approvals")
public class WorkflowApproval extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "step_run_id", nullable = false, updatable = false)
    private UUID stepRunId;

    @Column(nullable = false)
    private String status = "PENDING";

    private String reason;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected WorkflowApproval() {
    }

    WorkflowApproval(UUID tenantId, UUID runId, UUID stepRunId, String reason) {
        this.tenantId = tenantId;
        this.runId = runId;
        this.stepRunId = stepRunId;
        this.reason = reason;
    }

    void decide(boolean approved, UUID userId, String note) {
        this.status = approved ? "APPROVED" : "REJECTED";
        this.decidedByUserId = userId;
        this.decisionNote = note;
        this.decidedAt = Instant.now();
    }

    void reopen(String reason) {
        this.status = "PENDING";
        this.reason = reason;
        this.decidedByUserId = null;
        this.decisionNote = null;
        this.decidedAt = null;
    }

    public UUID getRunId() { return runId; }
    public UUID getStepRunId() { return stepRunId; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public UUID getDecidedByUserId() { return decidedByUserId; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
}
