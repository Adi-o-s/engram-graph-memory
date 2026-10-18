package com.memora.orchestration.agentdef;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configurable agent. The definition is behaviour and policy; {@code agentId} links it to the
 * {@code agents} row that is its identity (handoffs, inbox, API key, task assignment).
 */
@Entity
@Table(name = "agent_definitions")
public class AgentDefinition extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "def_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String instructions = "";

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] capabilities = new String[0];

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] tools = new String[0];

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> inputSchema = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> outputSchema = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory_policy", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> memoryPolicy = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_policy", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> contextPolicy = new HashMap<>();

    @Column(nullable = false)
    private String executor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> behavior = new HashMap<>();

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = 60;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "backoff_seconds", nullable = false)
    private int backoffSeconds = 2;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "version", nullable = false)
    private int revision = 1;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "source_template")
    private String sourceTemplate;

    @Column(name = "agent_id", nullable = false, updatable = false)
    private UUID agentId;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected AgentDefinition() {
    }

    AgentDefinition(UUID tenantId, String key, UUID agentId, UUID createdByUserId, String sourceTemplate) {
        this.tenantId = tenantId;
        this.key = key;
        this.agentId = agentId;
        this.createdByUserId = createdByUserId;
        this.sourceTemplate = sourceTemplate;
    }

    void apply(AgentDefinitionDtos.AgentDefinitionRequest r) {
        this.name = r.name().trim();
        this.description = r.description();
        this.role = r.role().trim();
        this.instructions = r.instructions() == null ? "" : r.instructions();
        this.capabilities = r.capabilities() == null ? new String[0] : r.capabilities().stream().map(String::trim).distinct().toArray(String[]::new);
        this.tools = r.tools() == null ? new String[0] : r.tools().stream().map(String::trim).distinct().toArray(String[]::new);
        this.inputSchema = copy(r.inputSchema());
        this.outputSchema = copy(r.outputSchema());
        this.memoryPolicy = copy(r.memoryPolicy());
        this.contextPolicy = copy(r.contextPolicy());
        this.executor = r.executor();
        this.behavior = copy(r.behavior());
        this.timeoutSeconds = r.timeoutSeconds() == null ? 60 : r.timeoutSeconds();
        this.maxAttempts = r.maxAttempts() == null ? 3 : r.maxAttempts();
        this.backoffSeconds = r.backoffSeconds() == null ? 2 : r.backoffSeconds();
        this.updatedAt = Instant.now();
    }

    void bumpRevision() {
        this.revision++;
    }

    void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    private static Map<String, Object> copy(Map<String, Object> m) {
        return m == null ? new HashMap<>() : new HashMap<>(m);
    }

    public AgentSnapshot snapshot(String agentName) {
        return new AgentSnapshot(getId(), key, name, role, description, instructions, List.copyOf(Arrays.asList(capabilities)),
                List.copyOf(Arrays.asList(tools)), inputSchema, outputSchema, memoryPolicy, contextPolicy, executor, behavior,
                timeoutSeconds, maxAttempts, backoffSeconds, revision, agentId, agentName);
    }

    public UUID getTenantId() { return tenantId; }
    public String getKey() { return key; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getRole() { return role; }
    public String getInstructions() { return instructions; }
    public List<String> getCapabilities() { return Arrays.asList(capabilities); }
    public List<String> getTools() { return Arrays.asList(tools); }
    public Map<String, Object> getInputSchema() { return inputSchema; }
    public Map<String, Object> getOutputSchema() { return outputSchema; }
    public Map<String, Object> getMemoryPolicy() { return memoryPolicy; }
    public Map<String, Object> getContextPolicy() { return contextPolicy; }
    public String getExecutor() { return executor; }
    public Map<String, Object> getBehavior() { return behavior; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getBackoffSeconds() { return backoffSeconds; }
    public String getStatus() { return status; }
    public int getRevision() { return revision; }
    public Long getLockVersion() { return lockVersion; }
    public String getSourceTemplate() { return sourceTemplate; }
    public UUID getAgentId() { return agentId; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
