package com.memora.agent;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agents")
public class Agent extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgentRole role;

    private String description;

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] capabilities = new String[0];

    @Column(name = "api_key_hash")
    private String apiKeyHash;

    @Column(name = "api_key_prefix")
    private String apiKeyPrefix;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "handoffs_received", nullable = false, insertable = false, updatable = false)
    private long handoffsReceived;

    @Column(name = "handoffs_sent", nullable = false, insertable = false, updatable = false)
    private long handoffsSent;

    @Column(name = "last_seen_at", insertable = false, updatable = false)
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Agent() {
    }

    public Agent(UUID tenantId, String name, AgentRole role, String description, String[] capabilities) {
        this.tenantId = tenantId;
        this.name = name;
        this.role = role;
        this.description = description;
        this.capabilities = capabilities == null ? new String[0] : capabilities;
    }

    public void setApiKey(String hash, String prefix) {
        this.apiKeyHash = hash;
        this.apiKeyPrefix = prefix;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    public String getDescription() {
        return description;
    }

    public String[] getCapabilities() {
        return capabilities;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public String getStatus() {
        return status;
    }

    public long getHandoffsReceived() {
        return handoffsReceived;
    }

    public long getHandoffsSent() {
        return handoffsSent;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
