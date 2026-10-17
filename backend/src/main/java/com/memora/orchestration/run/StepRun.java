package com.memora.orchestration.run;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One iteration of one workflow step. Claiming (READY → RUNNING) is a native, SKIP LOCKED update by the
 * scheduler; every other transition happens through this entity while the run row is locked.
 * {@code handoffId} is written only by the coordination mirror (a separate transaction), so JPA never
 * overwrites it.
 */
@Entity
@Table(name = "workflow_step_runs")
public class StepRun extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "step_key", nullable = false, updatable = false)
    private String stepKey;

    @Column(nullable = false, updatable = false)
    private int iteration;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(name = "agent_definition_id", updatable = false)
    private UUID agentDefinitionId;

    @Column(name = "agent_key", updatable = false)
    private String agentKey;

    @Column(name = "agent_version", updatable = false)
    private Integer agentVersion;

    @Column(updatable = false)
    private String executor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus status = StepStatus.PENDING;

    @Column(name = "depends_on", nullable = false, updatable = false, columnDefinition = "text[]")
    private String[] dependsOn = new String[0];

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> output;

    private String error;

    @Column(name = "error_kind")
    private String errorKind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ready_reason", columnDefinition = "jsonb")
    private Map<String, Object> readyReason;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Column(name = "handoff_id", insertable = false, updatable = false)
    private UUID handoffId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    protected StepRun() {
    }

    StepRun(UUID tenantId, UUID runId, String stepKey, int iteration, String name, UUID agentDefinitionId, String agentKey,
            Integer agentVersion, String executor, List<String> dependsOn, int maxAttempts, UUID taskId) {
        this.tenantId = tenantId;
        this.runId = runId;
        this.stepKey = stepKey;
        this.iteration = iteration;
        this.name = name;
        this.agentDefinitionId = agentDefinitionId;
        this.agentKey = agentKey;
        this.agentVersion = agentVersion;
        this.executor = executor;
        this.dependsOn = dependsOn.toArray(String[]::new);
        this.maxAttempts = maxAttempts;
        this.taskId = taskId;
    }

    void markReady(Map<String, Object> reason) {
        this.status = StepStatus.READY;
        this.readyReason = reason;
        this.readyAt = Instant.now();
        this.nextAttemptAt = null;
        this.error = null;
        this.errorKind = null;
    }

    void markWaitingApproval(Map<String, Object> reason) {
        this.status = StepStatus.WAITING_APPROVAL;
        this.readyReason = reason;
    }

    void markPending() {
        this.status = StepStatus.PENDING;
        this.error = null;
        this.errorKind = null;
    }

    void markBlocked(String why) {
        this.status = StepStatus.BLOCKED;
        this.error = why;
    }

    void markSkipped(Map<String, Object> reason) {
        this.status = StepStatus.SKIPPED;
        this.readyReason = reason;
        this.finishedAt = Instant.now();
    }

    void markSucceeded(Map<String, Object> output) {
        this.status = StepStatus.SUCCEEDED;
        this.output = output;
        this.error = null;
        this.errorKind = null;
        clearLease();
        this.finishedAt = Instant.now();
    }

    void markFailed(String error, String kind, Map<String, Object> partialOutput) {
        this.status = StepStatus.FAILED;
        this.error = error;
        this.errorKind = kind;
        if (partialOutput != null && !partialOutput.isEmpty()) {
            this.output = partialOutput;
        }
        clearLease();
        this.finishedAt = Instant.now();
    }

    void scheduleRetry(String error, Instant at) {
        this.status = StepStatus.READY;
        this.error = error;
        this.errorKind = "RETRYABLE";
        this.nextAttemptAt = at;
        this.readyAt = Instant.now();
        clearLease();
    }

    void markCancelled() {
        this.status = StepStatus.CANCELLED;
        clearLease();
        this.finishedAt = Instant.now();
    }

    /** Manual retry: one more attempt on top of whatever was used. */
    void grantAttempt() {
        if (this.attempts >= this.maxAttempts) {
            this.maxAttempts = this.attempts + 1;
        }
        this.finishedAt = null;
    }

    void setInput(Map<String, Object> input) { this.input = input; }

    void extendLease(Instant until) { this.leaseExpiresAt = until; }

    private void clearLease() {
        this.leaseOwner = null;
        this.leaseExpiresAt = null;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getRunId() { return runId; }
    public String getStepKey() { return stepKey; }
    public int getIteration() { return iteration; }
    public String getName() { return name; }
    public UUID getAgentDefinitionId() { return agentDefinitionId; }
    public String getAgentKey() { return agentKey; }
    public Integer getAgentVersion() { return agentVersion; }
    public String getExecutor() { return executor; }
    public StepStatus getStatus() { return status; }
    public List<String> getDependsOn() { return Arrays.asList(dependsOn); }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public Map<String, Object> getInput() { return input; }
    public Map<String, Object> getOutput() { return output; }
    public String getError() { return error; }
    public String getErrorKind() { return errorKind; }
    public Map<String, Object> getReadyReason() { return readyReason; }
    public String getLeaseOwner() { return leaseOwner; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public UUID getTaskId() { return taskId; }
    public UUID getHandoffId() { return handoffId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public Long getLockVersion() { return lockVersion; }
}
