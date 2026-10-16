package com.memora.memory;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MemoryDtos {

    private MemoryDtos() {
    }

    /** Everything needed to write a memory. Built by the API layer, the extractor pipeline or agents. */
    public record MemoryDraft(
            MemoryType type, String category, String content,
            String subject, String predicate, String objectValue,
            MemoryScope scope, UUID projectId, UUID conversationId, UUID taskId, UUID agentId,
            List<String> tags, Integer importance, Double confidence, Instant expiresAt, Map<String, Object> metadata,
            SourceType sourceType, UUID sourceMessageId, String sourceRef, String extractor) {

        public boolean isFact() {
            return subject != null && !subject.isBlank() && predicate != null && !predicate.isBlank();
        }

        public static Builder builder(MemoryType type, String content) {
            return new Builder(type, content);
        }

        public static final class Builder {
            private final MemoryType type;
            private final String content;
            private String category;
            private String subject;
            private String predicate;
            private String objectValue;
            private MemoryScope scope;
            private UUID projectId;
            private UUID conversationId;
            private UUID taskId;
            private UUID agentId;
            private List<String> tags = List.of();
            private Integer importance;
            private Double confidence;
            private Instant expiresAt;
            private Map<String, Object> metadata = Map.of();
            private SourceType sourceType;
            private UUID sourceMessageId;
            private String sourceRef;
            private String extractor;

            private Builder(MemoryType type, String content) {
                this.type = type;
                this.content = content;
            }

            public Builder category(String v) { this.category = v; return this; }
            public Builder fact(String subject, String predicate, String value) {
                this.subject = subject;
                this.predicate = predicate;
                this.objectValue = value;
                return this;
            }
            public Builder scope(MemoryScope v) { this.scope = v; return this; }
            public Builder project(UUID v) { this.projectId = v; return this; }
            public Builder conversation(UUID v) { this.conversationId = v; return this; }
            public Builder task(UUID v) { this.taskId = v; return this; }
            public Builder agent(UUID v) { this.agentId = v; return this; }
            public Builder tags(List<String> v) { this.tags = v == null ? List.of() : v; return this; }
            public Builder importance(Integer v) { this.importance = v; return this; }
            public Builder confidence(Double v) { this.confidence = v; return this; }
            public Builder expiresAt(Instant v) { this.expiresAt = v; return this; }
            public Builder metadata(Map<String, Object> v) { this.metadata = v == null ? Map.of() : v; return this; }
            public Builder source(SourceType type, UUID messageId, String ref) {
                this.sourceType = type;
                this.sourceMessageId = messageId;
                this.sourceRef = ref;
                return this;
            }
            public Builder extractor(String v) { this.extractor = v; return this; }

            public MemoryDraft build() {
                return new MemoryDraft(type, category, content, subject, predicate, objectValue, scope, projectId,
                        conversationId, taskId, agentId, tags, importance, confidence, expiresAt, metadata,
                        sourceType, sourceMessageId, sourceRef, extractor);
            }
        }
    }

    public record CreateMemoryRequest(
            @NotNull MemoryType type,
            @NotBlank @Size(max = 4000) String content,
            @Size(max = 40) String category,
            @Size(max = 200) String subject,
            @Size(max = 200) String predicate,
            @Size(max = 500) String value,
            MemoryScope scope,
            UUID projectId,
            UUID conversationId,
            UUID taskId,
            @Size(max = 20) List<@Size(max = 50) String> tags,
            @Min(1) @Max(5) Integer importance,
            @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            Instant expiresAt,
            Map<String, Object> metadata,
            @Size(max = 500) String sourceRef) {
    }

    public record UpdateMemoryRequest(
            @NotNull Long expectedLockVersion,
            @Min(1) @Max(5) Integer importance,
            @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            @Size(max = 20) List<@Size(max = 50) String> tags,
            @Size(max = 40) String category,
            Instant expiresAt) {
    }

    public record ReviseMemoryRequest(@NotBlank @Size(max = 4000) String content, @Size(max = 500) String value,
                                      Long expectedLockVersion, @Size(max = 500) String reason) {
    }

    public record RetractMemoryRequest(@Size(max = 500) String reason, Long expectedLockVersion) {
    }

    public enum WriteOutcome {
        /** A new memory was stored. */
        CREATED,
        /** A new fact was stored and the previous current fact for the same key was marked SUPERSEDED. */
        SUPERSEDED_PREVIOUS,
        /** The same fact or content already existed and is current; nothing new was stored. */
        DUPLICATE
    }

    public record MemoryWriteResult(MemoryView memory, WriteOutcome outcome, UUID supersededMemoryId) {
    }

    public record SourceView(SourceType type, UUID messageId, String ref, String extractor, UUID createdByUserId,
                             UUID createdByAgentId) {
    }

    public record MemoryView(
            UUID id, MemoryType type, String category, String content,
            String subject, String predicate, String value, String factKey,
            MemoryScope scope, String scopeKey, UUID userId, UUID projectId, UUID conversationId, UUID taskId, UUID agentId,
            List<String> tags, int importance, double confidence, MemoryStatus status, int version, Long lockVersion,
            UUID supersedesId, UUID supersededById, SourceView source, long accessCount, Instant lastAccessedAt,
            Instant expiresAt, Map<String, Object> metadata, Instant createdAt, Instant updatedAt, Double score) {

        public static MemoryView of(Memory m) {
            return new MemoryView(m.getId(), m.getType(), m.getCategory(), m.getContent(), m.getSubject(),
                    m.getPredicate(), m.getObjectValue(), m.getFactKey(), scopeOf(m.getScopeKey()), m.getScopeKey(),
                    m.getUserId(), m.getProjectId(), m.getConversationId(), m.getTaskId(), m.getAgentId(),
                    Arrays.asList(m.getTags()), m.getImportance(), m.getConfidence(), m.getStatus(), m.getRevision(),
                    m.getLockVersion(), m.getSupersedesId(), m.getSupersededById(),
                    new SourceView(m.getSourceType(), m.getSourceMessageId(), m.getSourceRef(), m.getExtractor(),
                            m.getCreatedByUserId(), m.getCreatedByAgentId()),
                    m.getAccessCount(), m.getLastAccessedAt(), m.getExpiresAt(), m.getMetadata(), m.getCreatedAt(),
                    m.getUpdatedAt(), null);
        }

        static MemoryScope scopeOf(String scopeKey) {
            if (scopeKey.startsWith("user:")) {
                return MemoryScope.USER;
            }
            return scopeKey.startsWith("project:") ? MemoryScope.PROJECT : MemoryScope.TENANT;
        }
    }

    public record HistoryView(UUID memoryId, String factKey, List<MemoryView> versions) {
    }
}
