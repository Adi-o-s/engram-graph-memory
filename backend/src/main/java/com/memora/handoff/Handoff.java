package com.memora.handoff;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A structured, persisted handoff between agents. Everything the receiver needs to continue lives
 * here or in referenced memories, never in the sender's process.
 */
@Entity
@Table(name = "handoffs")
public class Handoff extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "from_agent_id", nullable = false, updatable = false)
    private UUID fromAgentId;

    @Column(name = "to_agent_id", nullable = false, updatable = false)
    private UUID toAgentId;

    @Column(nullable = false, updatable = false)
    private String summary;

    @Column(name = "memory_refs", nullable = false, updatable = false, columnDefinition = "uuid[]")
    private UUID[] memoryRefs = new UUID[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> decisions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> artifacts = new HashMap<>();

    @Column(name = "next_action", nullable = false, updatable = false)
    private String nextAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HandoffStatus status = HandoffStatus.PENDING;

    @Column(name = "task_version", nullable = false, updatable = false)
    private long taskVersion;


    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false, updatable = false)
    private int maxAttempts = 3;

    @Column(name = "failure_reason")
    private String failureReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> result;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "claimed_by_agent_id")
    private UUID claimedByAgentId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Handoff() {
    }

    Handoff(UUID tenantId, UUID taskId, UUID fromAgentId, UUID toAgentId, String summary, UUID[] memoryRefs,
            List<String> decisions, Map<String, Object> artifacts, String nextAction, long taskVersion,
            int maxAttempts, String idempotencyKey) {
        this.tenantId = tenantId;
        this.taskId = taskId;
        this.fromAgentId = fromAgentId;
        this.toAgentId = toAgentId;
        this.summary = summary;
        this.memoryRefs = memoryRefs;
        this.decisions = decisions == null ? new ArrayList<>() : new ArrayList<>(decisions);
        this.artifacts = artifacts == null ? new HashMap<>() : new HashMap<>(artifacts);
        this.nextAction = nextAction;
        this.taskVersion = taskVersion;
        this.maxAttempts = maxAttempts;
        this.idempotencyKey = idempotencyKey;
    }

    void setTaskVersion(long taskVersion) {
        this.taskVersion = taskVersion;
    }

    void acknowledge(UUID agentId, Instant leaseUntil) {
        this.status = HandoffStatus.ACKNOWLEDGED;
        this.claimedByAgentId = agentId;
        this.attempts++;
        this.acknowledgedAt = Instant.now();
        this.leaseExpiresAt = leaseUntil;
        touch();
    }

    void complete(Map<String, Object> result) {
        this.status = HandoffStatus.COMPLETED;
        this.result = result;
        this.completedAt = Instant.now();
        this.leaseExpiresAt = null;
        touch();
    }

    void fail(String reason, Map<String, Object> result) {
        this.status = attempts >= maxAttempts ? HandoffStatus.DEAD_LETTER : HandoffStatus.FAILED;
        this.failureReason = reason;
        this.result = result;
        this.leaseExpiresAt = null;
        touch();
    }

    void requeue(String reason) {
        this.status = attempts >= maxAttempts ? HandoffStatus.DEAD_LETTER : HandoffStatus.PENDING;
        if (reason != null) {
            this.failureReason = reason;
        }
        this.claimedByAgentId = null;
        this.leaseExpiresAt = null;
        touch();
    }

    void reject(String reason) {
        this.status = HandoffStatus.REJECTED;
        this.failureReason = reason;
        this.leaseExpiresAt = null;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getTaskId() { return taskId; }
    public UUID getFromAgentId() { return fromAgentId; }
    public UUID getToAgentId() { return toAgentId; }
    public String getSummary() { return summary; }
    public UUID[] getMemoryRefs() { return memoryRefs; }
    public List<String> getDecisions() { return decisions; }
    public Map<String, Object> getArtifacts() { return artifacts; }
    public String getNextAction() { return nextAction; }
    public HandoffStatus getStatus() { return status; }
    public long getTaskVersion() { return taskVersion; }
    public int getAttempts() { return attempts; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getFailureReason() { return failureReason; }
    public Map<String, Object> getResult() { return result; }
    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public UUID getClaimedByAgentId() { return claimedByAgentId; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
