package com.memora.orchestration.agentdef;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable record of an agent definition as it was saved at one version. */
@Entity
@Table(name = "agent_definition_versions")
public class AgentDefinitionVersion extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "definition_id", nullable = false, updatable = false)
    private UUID definitionId;

    @Column(nullable = false, updatable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> snapshot;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AgentDefinitionVersion() {
    }

    AgentDefinitionVersion(UUID tenantId, UUID definitionId, int version, Map<String, Object> snapshot, UUID createdByUserId) {
        this.tenantId = tenantId;
        this.definitionId = definitionId;
        this.version = version;
        this.snapshot = snapshot;
        this.createdByUserId = createdByUserId;
    }

    public UUID getDefinitionId() { return definitionId; }
    public int getVersion() { return version; }
    public Map<String, Object> getSnapshot() { return snapshot; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
}
