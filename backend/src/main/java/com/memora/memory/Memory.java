package com.memora.memory;

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

/**
 * A derived memory record. Content changes never overwrite a row: a revision creates a new row
 * that supersedes the old one, so history and provenance are preserved.
 */
@Entity
@Table(name = "memories")
public class Memory extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "project_id", updatable = false)
    private UUID projectId;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "task_id", updatable = false)
    private UUID taskId;

    @Column(name = "agent_id", updatable = false)
    private UUID agentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "memory_type", nullable = false, updatable = false)
    private MemoryType type;

    private String category;

    @Column(nullable = false, updatable = false)
    private String content;

    @Column(updatable = false)
    private String subject;

    @Column(updatable = false)
    private String predicate;

    @Column(name = "object_value", updatable = false)
    private String objectValue;

    @Column(name = "fact_key", updatable = false)
    private String factKey;

    @Column(name = "scope_key", nullable = false, updatable = false)
    private String scopeKey;

    @Column(name = "search_text", nullable = false)
    private String searchText;

    @Column(nullable = false, columnDefinition = "text[]")
    private String[] tags = new String[0];

    @Column(nullable = false)
    private int importance = 3;

    @Column(nullable = false)
    private double confidence = 1.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemoryStatus status = MemoryStatus.CURRENT;

    @Column(name = "version", nullable = false, updatable = false)
    private int revision = 1;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "supersedes_id", updatable = false)
    private UUID supersedesId;

    @Column(name = "superseded_by_id")
    private UUID supersededById;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false)
    private SourceType sourceType;

    @Column(name = "source_message_id", updatable = false)
    private UUID sourceMessageId;

    @Column(name = "source_ref", updatable = false)
    private String sourceRef;

    @Column(updatable = false)
    private String extractor;

    @Column(name = "created_by_user_id", updatable = false)
    private UUID createdByUserId;

    @Column(name = "created_by_agent_id", updatable = false)
    private UUID createdByAgentId;

    @Column(name = "access_count", nullable = false, insertable = false, updatable = false)
    private long accessCount;

    @Column(name = "last_accessed_at", insertable = false, updatable = false)
    private Instant lastAccessedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Memory() {
    }

    Memory(UUID tenantId, MemoryType type, String content, String scopeKey) {
        this.tenantId = tenantId;
        this.type = type;
        this.content = content;
        this.scopeKey = scopeKey;
    }

    void markSuperseded() {
        this.status = MemoryStatus.SUPERSEDED;
        this.updatedAt = Instant.now();
    }

    void linkSuccessor(UUID successorId) {
        this.supersededById = successorId;
    }

    void retract() {
        this.status = MemoryStatus.RETRACTED;
        this.updatedAt = Instant.now();
    }

    void touch() {
        this.updatedAt = Instant.now();
    }

    // Package-private setters: only MemoryService builds and mutates memories.
    void setUserId(UUID userId) { this.userId = userId; }
    void setProjectId(UUID projectId) { this.projectId = projectId; }
    void setConversationId(UUID conversationId) { this.conversationId = conversationId; }
    void setTaskId(UUID taskId) { this.taskId = taskId; }
    void setAgentId(UUID agentId) { this.agentId = agentId; }
    void setCategory(String category) { this.category = category; }
    void setFact(String subject, String predicate, String objectValue, String factKey) {
        this.subject = subject;
        this.predicate = predicate;
        this.objectValue = objectValue;
        this.factKey = factKey;
    }
    void setSearchText(String searchText) { this.searchText = searchText; }
    void setTags(String[] tags) { this.tags = tags; }
    void setImportance(int importance) { this.importance = importance; }
    void setConfidence(double confidence) { this.confidence = confidence; }
    void setRevision(int revision) { this.revision = revision; }
    void setSupersedesId(UUID supersedesId) { this.supersedesId = supersedesId; }
    void setSource(SourceType sourceType, UUID sourceMessageId, String sourceRef, String extractor) {
        this.sourceType = sourceType;
        this.sourceMessageId = sourceMessageId;
        this.sourceRef = sourceRef;
        this.extractor = extractor;
    }
    void setCreatedBy(UUID userId, UUID agentId) {
        this.createdByUserId = userId;
        this.createdByAgentId = agentId;
    }
    void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    void setMetadata(Map<String, Object> metadata) { this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata); }
    void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public UUID getProjectId() { return projectId; }
    public UUID getConversationId() { return conversationId; }
    public UUID getTaskId() { return taskId; }
    public UUID getAgentId() { return agentId; }
    public MemoryType getType() { return type; }
    public String getCategory() { return category; }
    public String getContent() { return content; }
    public String getSubject() { return subject; }
    public String getPredicate() { return predicate; }
    public String getObjectValue() { return objectValue; }
    public String getFactKey() { return factKey; }
    public String getScopeKey() { return scopeKey; }
    public String[] getTags() { return tags; }
    public int getImportance() { return importance; }
    public double getConfidence() { return confidence; }
    public MemoryStatus getStatus() { return status; }
    public int getRevision() { return revision; }
    public Long getLockVersion() { return lockVersion; }
    public UUID getSupersedesId() { return supersedesId; }
    public UUID getSupersededById() { return supersededById; }
    public SourceType getSourceType() { return sourceType; }
    public UUID getSourceMessageId() { return sourceMessageId; }
    public String getSourceRef() { return sourceRef; }
    public String getExtractor() { return extractor; }
    public UUID getCreatedByUserId() { return createdByUserId; }
    public UUID getCreatedByAgentId() { return createdByAgentId; }
    public long getAccessCount() { return accessCount; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
