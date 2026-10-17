package com.memora.orchestration.run;

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

/** One execution of a workflow version, with the spec and agent snapshots it was started with. */
@Entity
@Table(name = "workflow_runs")
public class WorkflowRun extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "workflow_version", nullable = false, updatable = false)
    private int workflowVersion;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(updatable = false)
    private String goal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputs = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> spec = new HashMap<>();

    /** Agent key → pinned {@code AgentSnapshot} (as JSON). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> agents = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> options = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> completion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> outputs;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "started_by_user_id", updatable = false)
    private UUID startedByUserId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected WorkflowRun() {
    }

    WorkflowRun(UUID tenantId, UUID projectId, UUID workflowId, int workflowVersion, String name, String goal,
                Map<String, Object> inputs, Map<String, Object> spec, Map<String, Object> agents, Map<String, Object> options,
                UUID startedByUserId, String idempotencyKey) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.workflowId = workflowId;
        this.workflowVersion = workflowVersion;
        this.name = name;
        this.goal = goal;
        this.inputs = inputs;
        this.spec = spec;
        this.agents = agents;
        this.options = options;
        this.startedByUserId = startedByUserId;
        this.idempotencyKey = idempotencyKey;
    }

    void setStatus(RunStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
        if (status.isFinished()) {
            this.finishedAt = Instant.now();
        } else {
            this.finishedAt = null;
        }
    }

    void setCompletion(Map<String, Object> completion) { this.completion = completion; }
    void setOutputs(Map<String, Object> outputs) { this.outputs = outputs; }
    void setTaskId(UUID taskId) { this.taskId = taskId; }

    public UUID getTenantId() { return tenantId; }
    public UUID getProjectId() { return projectId; }
    public UUID getWorkflowId() { return workflowId; }
    public int getWorkflowVersion() { return workflowVersion; }
    public String getName() { return name; }
    public String getGoal() { return goal; }
    public Map<String, Object> getInputs() { return inputs; }
    public Map<String, Object> getSpec() { return spec; }
    public Map<String, Object> getAgents() { return agents; }
    public Map<String, Object> getOptions() { return options; }
    public RunStatus getStatus() { return status; }
    public Map<String, Object> getCompletion() { return completion; }
    public Map<String, Object> getOutputs() { return outputs; }
    public UUID getTaskId() { return taskId; }
    public UUID getStartedByUserId() { return startedByUserId; }
    public Long getLockVersion() { return lockVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
