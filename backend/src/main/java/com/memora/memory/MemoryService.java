package com.memora.memory;

import com.memora.agent.AgentService;
import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Idempotency;
import com.memora.common.Json;
import com.memora.conversation.Conversation;
import com.memora.conversation.ConversationService;
import com.memora.extraction.SensitiveContentGuard;
import com.memora.memory.MemoryDtos.HistoryView;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryDtos.MemoryWriteResult;
import com.memora.memory.MemoryDtos.ReviseMemoryRequest;
import com.memora.memory.MemoryDtos.UpdateMemoryRequest;
import com.memora.memory.MemoryDtos.WriteOutcome;
import com.memora.project.ProjectService;
import com.memora.security.Actor;
import com.memora.task.TaskService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes and reads memory records.
 *
 * <p>Conflict rule (deterministic): a fact is identified by {@code scope_key + fact_key}, where the
 * fact key is {@code subject|predicate}. Writing a fact whose key already has a CURRENT row:
 * <ul>
 *   <li>same value: nothing new is stored (DUPLICATE); confidence is raised to the higher value</li>
 *   <li>different value: the old row becomes SUPERSEDED (kept, linked) and the new row is CURRENT
 *       with version + 1 (SUPERSEDED_PREVIOUS)</li>
 * </ul>
 * Facts with different keys or scopes never conflict. A partial unique index guarantees at most one
 * CURRENT row per key even under concurrent writers. Writers of one fact are serialized with a
 * transaction-scoped advisory lock; the unique index remains the safety net, and a writer that still
 * loses a race retries.
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int MAX_FACT_RETRIES = 4;

    private final MemoryRepository memories;
    private final ProjectService projects;
    private final ConversationService conversations;
    private final TaskService tasks;
    private final AgentService agents;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    public MemoryService(MemoryRepository memories, ProjectService projects, ConversationService conversations,
                         TaskService tasks, AgentService agents, AuditService audit, TransactionTemplate tx,
                         JdbcTemplate jdbc) {
        this.memories = memories;
        this.projects = projects;
        this.conversations = conversations;
        this.tasks = tasks;
        this.agents = agents;
        this.audit = audit;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    public MemoryWriteResult create(Actor actor, MemoryDraft draft, String idempotencyKey) {
        validateDraft(draft);
        String key = Idempotency.normalize(idempotencyKey);
        for (int attempt = 1; ; attempt++) {
            try {
                return Idempotency.createOnce(key,
                        () -> tx.execute(s -> memories.findByTenantIdAndIdempotencyKey(actor.tenantId(), key)
                                .map(m -> new MemoryWriteResult(MemoryView.of(m), WriteOutcome.DUPLICATE, null))),
                        () -> tx.execute(s -> createInTransaction(actor, draft, key)));
            } catch (DataIntegrityViolationException e) {
                // Another writer committed the same fact first; re-run against the now-current row.
                if (attempt >= MAX_FACT_RETRIES || !isCurrentFactConflict(e)) {
                    throw e;
                }
                log.debug("Concurrent write of fact {}|{}, retrying (attempt {})", draft.subject(), draft.predicate(), attempt);
            }
        }
    }

    private MemoryWriteResult createInTransaction(Actor actor, MemoryDraft draft, String key) {
        UUID projectId = draft.projectId();
        if (draft.conversationId() != null) {
            Conversation c = conversations.requireVisible(actor, draft.conversationId());
            if (projectId == null) {
                projectId = c.getProjectId();
            } else if (c.getProjectId() != null && !c.getProjectId().equals(projectId)) {
                throw ApiException.badRequest("conversationId belongs to a different project");
            }
        }
        projects.requireInTenant(actor, projectId);
        tasks.requireExists(actor, draft.taskId());
        if (draft.agentId() != null) {
            agents.require(actor, draft.agentId());
        }
        MemoryScope scope = resolveScope(draft.scope(), projectId);
        if (scope == MemoryScope.USER && !actor.isUser()) {
            throw ApiException.badRequest("USER-scoped memories must be written by the user they belong to");
        }
        String scopeKey = scopeKey(actor, scope, projectId);

        if (draft.isFact()) {
            String factKey = factKey(draft.subject(), draft.predicate());
            String value = normalizeValue(draft.objectValue());
            // Serialize writers of the same fact for the rest of this transaction. Without this, a writer
            // waiting on the old row's lock finds it already superseded and has to retry.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                    actor.tenantId() + "|" + scopeKey + "|" + factKey);
            Optional<Memory> current = memories.lockCurrentFact(actor.tenantId(), scopeKey, factKey);
            if (current.isPresent() && value.equals(current.get().getObjectValue())) {
                Memory existing = current.get();
                double confidence = Math.max(existing.getConfidence(), draft.confidence() == null ? 1.0 : draft.confidence());
                if (confidence != existing.getConfidence()) {
                    existing.setConfidence(confidence);
                    existing.touch();
                }
                return new MemoryWriteResult(MemoryView.of(existing), WriteOutcome.DUPLICATE, null);
            }
            Memory fresh = build(actor, draft, scope, scopeKey, projectId, key);
            fresh.setFact(clean(draft.subject()), clean(draft.predicate()), value, factKey);
            if (current.isPresent()) {
                Memory previous = current.get();
                // Order matters for the partial unique index: retire the old row before inserting the new one.
                previous.markSuperseded();
                memories.flush();
                fresh.setRevision(previous.getRevision() + 1);
                fresh.setSupersedesId(previous.getId());
                memories.save(fresh);
                memories.flush();
                previous.linkSuccessor(fresh.getId());
                memories.flush();
                audit.record(actor, "memory.superseded", "memory", previous.getId(),
                        Json.map("supersededBy", fresh.getId(), "factKey", factKey, "oldValue", previous.getObjectValue(),
                                "newValue", value));
                audit.record(actor, "memory.created", "memory", fresh.getId(),
                        Json.map("type", fresh.getType(), "factKey", factKey, "version", fresh.getRevision()));
                return new MemoryWriteResult(MemoryView.of(fresh), WriteOutcome.SUPERSEDED_PREVIOUS, previous.getId());
            }
            memories.save(fresh);
            memories.flush();
            audit.record(actor, "memory.created", "memory", fresh.getId(),
                    Json.map("type", fresh.getType(), "factKey", factKey, "version", 1));
            return new MemoryWriteResult(MemoryView.of(fresh), WriteOutcome.CREATED, null);
        }

        List<Memory> same = memories.findSameContent(actor.tenantId(), scopeKey, draft.type(), draft.content().trim());
        if (!same.isEmpty()) {
            return new MemoryWriteResult(MemoryView.of(same.get(0)), WriteOutcome.DUPLICATE, null);
        }
        Memory fresh = build(actor, draft, scope, scopeKey, projectId, key);
        memories.save(fresh);
        memories.flush();
        audit.record(actor, "memory.created", "memory", fresh.getId(), Json.map("type", fresh.getType()));
        return new MemoryWriteResult(MemoryView.of(fresh), WriteOutcome.CREATED, null);
    }

    @Transactional(readOnly = true)
    public MemoryView get(Actor actor, UUID id) {
        return MemoryView.of(requireVisible(actor, id));
    }

    /** Metadata-only edits (importance, tags, confidence, expiry) happen in place, guarded by lockVersion. */
    @Transactional
    public MemoryView update(Actor actor, UUID id, UpdateMemoryRequest request) {
        Memory m = requireVisible(actor, id);
        if (!m.getLockVersion().equals(request.expectedLockVersion())) {
            throw ApiException.staleVersion("Memory", id, request.expectedLockVersion(), m.getLockVersion());
        }
        if (request.importance() != null) {
            m.setImportance(request.importance());
        }
        if (request.confidence() != null) {
            m.setConfidence(request.confidence());
        }
        if (request.tags() != null) {
            m.setTags(normalizeTags(request.tags()));
            m.setSearchText(searchText(m.getContent(), m.getSubject(), m.getPredicate(), m.getObjectValue(),
                    m.getCategory(), request.tags()));
        }
        if (request.category() != null) {
            m.setCategory(request.category());
        }
        if (request.expiresAt() != null) {
            m.setExpiresAt(request.expiresAt());
        }
        m.touch();
        memories.flush();
        audit.record(actor, "memory.updated", "memory", m.getId(), Json.map("lockVersion", m.getLockVersion()));
        return MemoryView.of(m);
    }

    /** Content changes create a new version that supersedes the old one; the old row is kept as history. */
    public MemoryWriteResult revise(Actor actor, UUID id, ReviseMemoryRequest request) {
        Memory original = tx.execute(s -> requireVisible(actor, id));
        if (original.getStatus() != MemoryStatus.CURRENT) {
            throw ApiException.conflict("not_current", "Only CURRENT memories can be revised", Map.of("status", original.getStatus()));
        }
        if (request.expectedLockVersion() != null && !original.getLockVersion().equals(request.expectedLockVersion())) {
            throw ApiException.staleVersion("Memory", id, request.expectedLockVersion(), original.getLockVersion());
        }
        if (original.getScopeKey().startsWith("user:") && !original.getScopeKey().equals("user:" + actor.userId())) {
            throw ApiException.forbidden("Only the owner can revise a personal (USER-scoped) memory");
        }
        Map<String, Object> metadata = new HashMap<>(original.getMetadata());
        if (request.reason() != null) {
            metadata.put("revisionReason", request.reason());
        }
        MemoryDraft.Builder draft = MemoryDraft.builder(original.getType(), request.content())
                .category(original.getCategory())
                .scope(MemoryView.scopeOf(original.getScopeKey()))
                .project(original.getProjectId()).conversation(original.getConversationId()).task(original.getTaskId())
                .agent(original.getAgentId()).tags(List.of(original.getTags())).importance(original.getImportance())
                .metadata(metadata)
                .source(actor.isAgent() ? SourceType.AGENT : SourceType.API, null, "revision-of:" + original.getId());
        if (original.getFactKey() != null) {
            String value = request.value() != null ? request.value() : request.content();
            draft.fact(original.getSubject(), original.getPredicate(), value);
            return create(actor, draft.build(), null);
        }
        validateDraft(draft.build());
        return tx.execute(s -> {
            Memory locked = memories.lockById(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Memory", id));
            if (locked.getStatus() != MemoryStatus.CURRENT) {
                throw ApiException.conflict("not_current", "The memory was changed concurrently", Map.of());
            }
            Memory fresh = build(actor, draft.build(), MemoryView.scopeOf(locked.getScopeKey()), locked.getScopeKey(),
                    locked.getProjectId(), null);
            fresh.setRevision(locked.getRevision() + 1);
            fresh.setSupersedesId(locked.getId());
            locked.markSuperseded();
            memories.flush();
            memories.save(fresh);
            memories.flush();
            locked.linkSuccessor(fresh.getId());
            audit.record(actor, "memory.revised", "memory", locked.getId(), Json.map("supersededBy", fresh.getId()));
            return new MemoryWriteResult(MemoryView.of(fresh), WriteOutcome.SUPERSEDED_PREVIOUS, locked.getId());
        });
    }

    @Transactional
    public MemoryView retract(Actor actor, UUID id, String reason, Long expectedLockVersion) {
        Memory m = requireVisible(actor, id);
        if (expectedLockVersion != null && !m.getLockVersion().equals(expectedLockVersion)) {
            throw ApiException.staleVersion("Memory", id, expectedLockVersion, m.getLockVersion());
        }
        if (m.getStatus() != MemoryStatus.CURRENT) {
            throw ApiException.conflict("not_current", "Only CURRENT memories can be retracted", Map.of("status", m.getStatus()));
        }
        Map<String, Object> metadata = new HashMap<>(m.getMetadata());
        metadata.put("retractedReason", reason == null ? "unspecified" : reason);
        m.setMetadata(metadata);
        m.retract();
        memories.flush();
        audit.record(actor, "memory.retracted", "memory", m.getId(), Json.map("reason", reason));
        return MemoryView.of(m);
    }

    /** All versions of a fact (or of a revision chain), oldest first. */
    @Transactional(readOnly = true)
    public HistoryView history(Actor actor, UUID id) {
        Memory m = requireVisible(actor, id);
        if (m.getFactKey() != null) {
            return new HistoryView(id, m.getFactKey(),
                    memories.factHistory(actor.tenantId(), m.getScopeKey(), m.getFactKey()).stream().map(MemoryView::of).toList());
        }
        List<Memory> chain = new ArrayList<>();
        Memory cursor = m;
        Set<UUID> seen = new LinkedHashSet<>();
        while (cursor.getSupersedesId() != null && seen.add(cursor.getId()) && seen.size() < 100) {
            cursor = memories.findByTenantIdAndId(actor.tenantId(), cursor.getSupersedesId()).orElse(null);
            if (cursor == null) {
                break;
            }
        }
        cursor = cursor == null ? m : cursor;
        seen.clear();
        while (cursor != null && seen.add(cursor.getId()) && seen.size() < 100) {
            chain.add(cursor);
            UUID next = cursor.getSupersededById();
            cursor = next == null ? null : memories.findByTenantIdAndId(actor.tenantId(), next).orElse(null);
        }
        return new HistoryView(id, null, chain.stream().map(MemoryView::of).toList());
    }

    @Transactional(readOnly = true)
    public List<MemoryView> fromMessage(Actor actor, UUID messageId) {
        return memories.findByTenantIdAndSourceMessageIdOrderByCreatedAt(actor.tenantId(), messageId).stream()
                .filter(m -> visible(actor, m)).map(MemoryView::of).toList();
    }

    /** Validates handoff memory references: they must exist, be visible to the caller and belong to its tenant. */
    @Transactional(readOnly = true)
    public List<MemoryView> requireAllVisible(Actor actor, Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<UUID> unique = new LinkedHashSet<>(ids);
        List<Memory> found = memories.findByTenantIdAndIdIn(actor.tenantId(), unique).stream()
                .filter(m -> visible(actor, m)).toList();
        if (found.size() != unique.size()) {
            Set<UUID> missing = new LinkedHashSet<>(unique);
            found.forEach(m -> missing.remove(m.getId()));
            throw ApiException.notFound("Memory", missing.iterator().next());
        }
        return found.stream().map(MemoryView::of).toList();
    }

    @Transactional
    public void recordAccess(Actor actor, Collection<UUID> ids) {
        if (!ids.isEmpty()) {
            memories.recordAccess(actor.tenantId(), ids);
        }
    }

    Memory requireVisible(Actor actor, UUID id) {
        Memory m = memories.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Memory", id));
        if (!visible(actor, m)) {
            throw ApiException.notFound("Memory", id);
        }
        return m;
    }

    static boolean visible(Actor actor, Memory m) {
        return actor.seesAllUserData() || !m.getScopeKey().startsWith("user:")
                || m.getScopeKey().equals("user:" + actor.userId());
    }

    private Memory build(Actor actor, MemoryDraft d, MemoryScope scope, String scopeKey, UUID projectId, String key) {
        Memory m = new Memory(actor.tenantId(), d.type(), d.content().trim(), scopeKey);
        m.setUserId(scope == MemoryScope.USER ? actor.userId() : null);
        m.setProjectId(projectId);
        m.setConversationId(d.conversationId());
        m.setTaskId(d.taskId());
        m.setAgentId(d.agentId() != null ? d.agentId() : actor.agentId());
        m.setCategory(d.category());
        m.setTags(normalizeTags(d.tags()));
        m.setImportance(d.importance() == null ? 3 : d.importance());
        m.setConfidence(d.confidence() == null ? 1.0 : d.confidence());
        m.setExpiresAt(d.expiresAt());
        m.setMetadata(d.metadata());
        m.setSearchText(searchText(d.content(), d.subject(), d.predicate(), d.objectValue(), d.category(), d.tags()));
        SourceType sourceType = d.sourceType() != null ? d.sourceType() : actor.isAgent() ? SourceType.AGENT : SourceType.API;
        m.setSource(sourceType, d.sourceMessageId(), d.sourceRef(), d.extractor());
        m.setCreatedBy(actor.userId(), actor.agentId());
        m.setIdempotencyKey(key);
        return m;
    }

    private static void validateDraft(MemoryDraft d) {
        if (d.type() == null || d.content() == null || d.content().isBlank()) {
            throw ApiException.badRequest("type and content are required");
        }
        if (SensitiveContentGuard.looksSensitive(d.content()) || SensitiveContentGuard.looksSensitive(d.objectValue())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "sensitive_content",
                    "The content looks like a credential or secret; Memora does not store secrets as memory");
        }
        boolean hasSubject = d.subject() != null && !d.subject().isBlank();
        boolean hasPredicate = d.predicate() != null && !d.predicate().isBlank();
        if (hasSubject != hasPredicate) {
            throw ApiException.badRequest("subject and predicate must be provided together");
        }
        if (d.isFact()) {
            if (d.type() != MemoryType.SEMANTIC) {
                throw ApiException.badRequest("Only SEMANTIC memories can carry subject/predicate facts");
            }
            if (d.objectValue() == null || d.objectValue().isBlank()) {
                throw ApiException.badRequest("A fact needs a value");
            }
        }
    }

    private static MemoryScope resolveScope(MemoryScope requested, UUID projectId) {
        if (requested != null) {
            if (requested == MemoryScope.PROJECT && projectId == null) {
                throw ApiException.badRequest("PROJECT scope needs a projectId (or a conversation that has one)");
            }
            return requested;
        }
        return projectId != null ? MemoryScope.PROJECT : MemoryScope.TENANT;
    }

    private static String scopeKey(Actor actor, MemoryScope scope, UUID projectId) {
        return switch (scope) {
            case USER -> "user:" + actor.userId();
            case PROJECT -> "project:" + projectId;
            case TENANT -> "tenant";
        };
    }

    static String factKey(String subject, String predicate) {
        return clean(subject).toLowerCase(Locale.ROOT) + "|" + clean(predicate).toLowerCase(Locale.ROOT);
    }

    private static String clean(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeValue(String v) {
        return clean(v).toLowerCase(Locale.ROOT);
    }

    private static String[] normalizeTags(List<String> tags) {
        if (tags == null) {
            return new String[0];
        }
        return tags.stream().filter(t -> t != null && !t.isBlank()).map(t -> t.trim().toLowerCase(Locale.ROOT))
                .distinct().toArray(String[]::new);
    }

    private static String searchText(String content, String subject, String predicate, String value, String category,
                                     List<String> tags) {
        StringBuilder sb = new StringBuilder(content);
        for (String part : new String[]{subject, predicate == null ? null : predicate.replace('.', ' '), value, category}) {
            if (part != null) {
                sb.append(' ').append(part);
            }
        }
        if (tags != null) {
            tags.forEach(t -> sb.append(' ').append(t));
        }
        return sb.toString();
    }

    private static boolean isCurrentFactConflict(DataIntegrityViolationException e) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        return message.contains("uq_memories_current_fact");
    }
}
