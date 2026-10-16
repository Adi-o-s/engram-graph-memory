package com.memora.conversation;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An original conversation message. Derived memories reference it; it is never rewritten. */
@Entity
@Table(name = "messages")
public class Message extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "conversation_id", nullable = false, updatable = false)
    private UUID conversationId;

    @Column(nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MessageRole role;

    @Column(name = "author_user_id", updatable = false)
    private UUID authorUserId;

    @Column(name = "author_agent_id", updatable = false)
    private UUID authorAgentId;

    @Column(nullable = false, updatable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Message() {
    }

    public Message(UUID tenantId, UUID conversationId, long seq, MessageRole role, UUID authorUserId,
                   UUID authorAgentId, String content, Map<String, Object> metadata, String idempotencyKey) {
        this.tenantId = tenantId;
        this.conversationId = conversationId;
        this.seq = seq;
        this.role = role;
        this.authorUserId = authorUserId;
        this.authorAgentId = authorAgentId;
        this.content = content;
        this.metadata = metadata == null ? new HashMap<>() : new HashMap<>(metadata);
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public long getSeq() {
        return seq;
    }

    public MessageRole getRole() {
        return role;
    }

    public UUID getAuthorUserId() {
        return authorUserId;
    }

    public UUID getAuthorAgentId() {
        return authorAgentId;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
