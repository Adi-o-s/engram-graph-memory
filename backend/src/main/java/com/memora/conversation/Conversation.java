package com.memora.conversation;

import com.memora.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(nullable = false)
    private String title;

    @Column(name = "session_id")
    private String sessionId;

    private String summary;

    // Maintained by an atomic UPDATE ... RETURNING when messages are added; never written by JPA.
    @Column(name = "message_count", nullable = false, insertable = false, updatable = false)
    private long messageCount;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "last_message_at", insertable = false, updatable = false)
    private Instant lastMessageAt;

    protected Conversation() {
    }

    public Conversation(UUID tenantId, UUID projectId, UUID ownerUserId, String title, String sessionId,
                        String idempotencyKey) {
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.ownerUserId = ownerUserId;
        this.title = title;
        this.sessionId = sessionId;
        this.idempotencyKey = idempotencyKey;
    }

    public void setSummary(String summary) {
        this.summary = summary;
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getTitle() {
        return title;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getSummary() {
        return summary;
    }

    public long getMessageCount() {
        return messageCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }
}
