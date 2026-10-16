package com.memora.conversation;

import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Idempotency;
import com.memora.common.Json;
import com.memora.common.PageResponse;
import com.memora.conversation.ConversationDtos.ConversationView;
import com.memora.conversation.ConversationDtos.MessageView;
import com.memora.project.ProjectService;
import com.memora.security.Actor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Conversations and their original messages. Messages get a gapless per-conversation sequence
 * number from an atomic UPDATE ... RETURNING, so concurrent writers never collide or reorder.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ProjectService projects;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ConversationService(ConversationRepository conversations, MessageRepository messages,
                               ProjectService projects, AuditService audit, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.conversations = conversations;
        this.messages = messages;
        this.projects = projects;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public ConversationView create(Actor actor, UUID projectId, String title, String sessionId, String idempotencyKey) {
        String key = Idempotency.normalize(idempotencyKey);
        return Idempotency.createOnce(key,
                () -> tx.execute(s -> conversations.findByTenantIdAndIdempotencyKey(actor.tenantId(), key)
                        .map(ConversationView::of)),
                () -> tx.execute(s -> {
                    projects.requireInTenant(actor, projectId);
                    Conversation c = conversations.save(new Conversation(actor.tenantId(), projectId, actor.userId(),
                            title, sessionId, key));
                    audit.record(actor, "conversation.created", "conversation", c.getId(), Json.map("title", title));
                    return ConversationView.of(c);
                }));
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationView> list(Actor actor, UUID projectId, Boolean mine, int page, int size) {
        projects.requireInTenant(actor, projectId);
        UUID ownerFilter = !actor.seesAllUserData() || Boolean.TRUE.equals(mine) ? actor.userId() : null;
        return PageResponse.of(conversations.search(actor.tenantId(), projectId, ownerFilter, PageRequest.of(page, size)),
                ConversationView::of);
    }

    @Transactional(readOnly = true)
    public ConversationView get(Actor actor, UUID id) {
        return ConversationView.of(requireVisible(actor, id));
    }

    /**
     * Visibility rule: members see conversations they own; admins and agents see all of the tenant's.
     * Anything else (including other tenants' IDs) is reported as not found.
     */
    @Transactional(readOnly = true)
    public Conversation requireVisible(Actor actor, UUID id) {
        Conversation c = conversations.findByTenantIdAndId(actor.tenantId(), id)
                .orElseThrow(() -> ApiException.notFound("Conversation", id));
        if (!actor.seesAllUserData() && c.getOwnerUserId() != null && !c.getOwnerUserId().equals(actor.userId())) {
            throw ApiException.notFound("Conversation", id);
        }
        return c;
    }

    /** Appends a message. Returns the existing message when the idempotency key was already used. */
    public AppendResult append(Actor actor, UUID conversationId, MessageRole requestedRole, String content,
                               Map<String, Object> metadata, String idempotencyKey) {
        String key = Idempotency.normalize(idempotencyKey);
        MessageRole role = resolveRole(actor, requestedRole);
        boolean[] created = {false};
        MessageView view = Idempotency.createOnce(key,
                () -> tx.execute(s -> messages.findByTenantIdAndIdempotencyKey(actor.tenantId(), key).map(MessageView::of)),
                () -> tx.execute(s -> {
                    Conversation conversation = requireVisible(actor, conversationId);
                    Long seq = jdbc.queryForObject("""
                            UPDATE conversations
                               SET message_count = message_count + 1, last_message_at = now(), updated_at = now()
                             WHERE tenant_id = ? AND id = ?
                            RETURNING message_count""", Long.class, actor.tenantId(), conversation.getId());
                    Message m = messages.save(new Message(actor.tenantId(), conversationId, seq, role, actor.userId(),
                            actor.agentId(), content, metadata, key));
                    created[0] = true;
                    return MessageView.of(m);
                }));
        if (!view.conversationId().equals(conversationId)) {
            throw ApiException.conflict("idempotency_key_reused",
                    "This Idempotency-Key was already used for a different conversation", Map.of());
        }
        Conversation conversation = tx.execute(s -> requireVisible(actor, conversationId));
        return new AppendResult(view, conversation, created[0]);
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageView> messages(Actor actor, UUID conversationId, int page, int size) {
        requireVisible(actor, conversationId);
        return PageResponse.of(messages.findByTenantIdAndConversationIdOrderBySeqAsc(actor.tenantId(), conversationId,
                PageRequest.of(page, size)), MessageView::of);
    }

    @Transactional(readOnly = true)
    public List<MessageView> messagesAfter(Actor actor, UUID conversationId, long afterSeq, int limit) {
        requireVisible(actor, conversationId);
        return messages.findByTenantIdAndConversationIdAndSeqGreaterThanOrderBySeqAsc(actor.tenantId(), conversationId,
                afterSeq, PageRequest.of(0, limit)).stream().map(MessageView::of).toList();
    }

    /** The newest {@code limit} messages in chronological order: the bounded slice an agent's context gets. */
    @Transactional(readOnly = true)
    public List<MessageView> recent(Actor actor, UUID conversationId, int limit) {
        requireVisible(actor, conversationId);
        List<MessageView> newestFirst = new ArrayList<>(messages.findByTenantIdAndConversationIdOrderBySeqDesc(
                actor.tenantId(), conversationId, PageRequest.of(0, limit)).stream().map(MessageView::of).toList());
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    @Transactional(readOnly = true)
    public MessageView message(Actor actor, UUID messageId) {
        Message m = messages.findByTenantIdAndId(actor.tenantId(), messageId)
                .orElseThrow(() -> ApiException.notFound("Message", messageId));
        requireVisible(actor, m.getConversationId());
        return MessageView.of(m);
    }

    @Transactional
    public ConversationView saveSummary(Actor actor, UUID conversationId, String summary) {
        Conversation c = requireVisible(actor, conversationId);
        c.setSummary(summary);
        conversations.flush();
        audit.record(actor, "conversation.summarized", "conversation", c.getId(), Map.of());
        return ConversationView.of(c);
    }

    private static MessageRole resolveRole(Actor actor, MessageRole requested) {
        if (actor.isAgent()) {
            return requested == MessageRole.ASSISTANT ? MessageRole.ASSISTANT : MessageRole.AGENT;
        }
        if (requested == MessageRole.AGENT) {
            throw ApiException.badRequest("Only agents can post AGENT messages");
        }
        return requested == null ? MessageRole.USER : requested;
    }

    public record AppendResult(MessageView message, Conversation conversation, boolean created) {
    }
}
