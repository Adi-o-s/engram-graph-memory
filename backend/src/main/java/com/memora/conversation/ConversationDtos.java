package com.memora.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class ConversationDtos {

    private ConversationDtos() {
    }

    public record CreateConversationRequest(UUID projectId, @NotBlank @Size(max = 300) String title,
                                            @Size(max = 100) String sessionId) {
    }

    public record CreateMessageRequest(MessageRole role, @NotBlank @Size(max = 20000) String content,
                                       Map<String, Object> metadata, Boolean extract) {
    }

    public record ConversationView(UUID id, UUID projectId, UUID ownerUserId, String title, String sessionId,
                                   String summary, long messageCount, Instant createdAt, Instant updatedAt,
                                   Instant lastMessageAt) {
        public static ConversationView of(Conversation c) {
            return new ConversationView(c.getId(), c.getProjectId(), c.getOwnerUserId(), c.getTitle(),
                    c.getSessionId(), c.getSummary(), c.getMessageCount(), c.getCreatedAt(), c.getUpdatedAt(),
                    c.getLastMessageAt());
        }
    }

    public record MessageView(UUID id, UUID conversationId, long seq, MessageRole role, UUID authorUserId,
                              UUID authorAgentId, String content, Map<String, Object> metadata, Instant createdAt) {
        public static MessageView of(Message m) {
            return new MessageView(m.getId(), m.getConversationId(), m.getSeq(), m.getRole(), m.getAuthorUserId(),
                    m.getAuthorAgentId(), m.getContent(), m.getMetadata(), m.getCreatedAt());
        }
    }
}
