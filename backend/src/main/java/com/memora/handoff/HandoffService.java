package com.memora.handoff;

import com.memora.agent.AgentRepository;
import com.memora.agent.AgentService;
import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Idempotency;
import com.memora.common.Json;
import com.memora.handoff.HandoffDtos.CreateHandoffRequest;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.memory.MemoryService;
import com.memora.security.Actor;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database-backed agent coordination. Each transition is a short transaction that locks the handoff
 * row, checks the current state and the actor, and writes the new state; agent work happens between
 * transactions, never inside one. Delivery is at-least-once: an agent that crashes after claiming
 * loses its lease and the handoff returns to the inbox.
 */
@Service
public class HandoffService {

    private final HandoffRepository handoffs;
    private final TaskService tasks;
    private final AgentService agents;
    private final AgentRepository agentRepository;
    private final MemoryService memories;
    private final AuditService audit;
    private final HandoffProperties properties;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher publisher;

    public HandoffService(HandoffRepository handoffs, TaskService tasks, AgentService agents,
                          AgentRepository agentRepository, MemoryService memories, AuditService audit,
                          HandoffProperties properties, TransactionTemplate tx, ApplicationEventPublisher publisher) {
        this.publisher = publisher;
        this.handoffs = handoffs;
        this.tasks = tasks;
        this.agents = agents;
        this.agentRepository = agentRepository;
        this.memories = memories;
        this.audit = audit;
        this.properties = properties;
        this.tx = tx;
    }

    /** Creates a handoff and reassigns the task to the receiver in one transaction. */
    public HandoffView create(Actor actor, CreateHandoffRequest request, String idempotencyKey) {
        String key = Idempotency.normalize(idempotencyKey);
        UUID fromAgentId = resolveSender(actor, request.fromAgentId());
        try {
            return Idempotency.createOnce(key,
                    () -> tx.execute(s -> handoffs.findByTenantIdAndIdempotencyKey(actor.tenantId(), key).map(HandoffView::of)),
                    () -> tx.execute(s -> createInTransaction(actor, request, fromAgentId, key)));
        } catch (DataIntegrityViolationException e) {
            throw activeHandoffConflict(e, request.taskId());
        }
    }

    private HandoffView createInTransaction(Actor actor, CreateHandoffRequest request, UUID fromAgentId, String key) {
        agents.require(actor, fromAgentId);
        agents.require(actor, request.toAgentId());
        if (fromAgentId.equals(request.toAgentId())) {
            throw ApiException.badRequest("An agent cannot hand off to itself");
        }
        List<UUID> refs = request.memoryRefs() == null ? List.of() : request.memoryRefs().stream().distinct().toList();
        memories.requireAllVisible(actor, refs);
        Handoff handoff = new Handoff(actor.tenantId(), request.taskId(), fromAgentId, request.toAgentId(),
                request.summary(), refs.toArray(UUID[]::new), request.decisions(), request.artifacts(),
                request.nextAction(), 0, request.maxAttempts() == null ? 3 : request.maxAttempts(), key);
        TaskView task = tasks.assignForHandoff(actor, request.taskId(), request.toAgentId(), request.expectedTaskVersion(),
                handoff.getId());
        handoff.setTaskVersion(task.version());
        handoffs.save(handoff);
        handoffs.flush();
        agentRepository.incrementSent(actor.tenantId(), fromAgentId);
        agentRepository.incrementReceived(actor.tenantId(), request.toAgentId());
        audit.record(actor, "handoff.created", "handoff", handoff.getId(),
                Json.map("taskId", request.taskId(), "from", fromAgentId, "to", request.toAgentId()));
        return HandoffView.of(handoff);
    }

    @Transactional(readOnly = true)
    public HandoffView get(Actor actor, UUID id) {
        return HandoffView.of(requireVisible(actor, id));
    }

    @Transactional(readOnly = true)
    public List<HandoffView> inbox(Actor actor, UUID agentId, Set<HandoffStatus> statuses) {
        if (actor.isAgent() && !actor.agentId().equals(agentId)) {
            throw ApiException.forbidden("Agents can only read their own inbox");
        }
        agents.require(actor, agentId);
        Set<HandoffStatus> wanted = statuses == null || statuses.isEmpty()
                ? EnumSet.of(HandoffStatus.PENDING, HandoffStatus.ACKNOWLEDGED) : statuses;
        return handoffs.findByTenantIdAndToAgentIdAndStatusInOrderByCreatedAtAsc(actor.tenantId(), agentId, wanted)
                .stream().map(HandoffView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<HandoffView> forTask(Actor actor, UUID taskId) {
        tasks.requireExists(actor, taskId);
        return handoffs.findByTenantIdAndTaskIdOrderByCreatedAtAsc(actor.tenantId(), taskId).stream()
                .filter(h -> canSee(actor, h)).map(HandoffView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<HandoffView> recent(Actor actor, HandoffStatus status, int limit) {
        return handoffs.recent(actor.tenantId(), status, PageRequest.of(0, limit)).stream()
                .filter(h -> canSee(actor, h)).map(HandoffView::of).toList();
    }

    /**
     * Claims a pending handoff under a lease. Repeating the call by the same agent while it holds the
     * claim returns the same result (idempotent); anyone else gets 409.
     */
    @Transactional
    public HandoffView acknowledge(Actor actor, UUID id) {
        Handoff h = lockForReceiver(actor, id);
        UUID claimer = actor.isAgent() ? actor.agentId() : h.getToAgentId();
        if (h.getStatus() == HandoffStatus.ACKNOWLEDGED && claimer.equals(h.getClaimedByAgentId())) {
            return HandoffView.of(h);
        }
        requireStatus(h, HandoffStatus.PENDING);
        h.acknowledge(claimer, Instant.now().plusSeconds(properties.leaseSeconds()));
        handoffs.flush();
        tasks.recordHandoffEvent(actor, h.getTaskId(), "handoff_acknowledged",
                Json.map("handoffId", h.getId(), "attempt", h.getAttempts()));
        audit.record(actor, "handoff.acknowledged", "handoff", h.getId(), Json.map("attempt", h.getAttempts()));
        return HandoffView.of(h);
    }

    @Transactional
    public HandoffView complete(Actor actor, UUID id, Map<String, Object> result) {
        Handoff h = lockForReceiver(actor, id);
        if (h.getStatus() == HandoffStatus.COMPLETED) {
            return HandoffView.of(h);
        }
        requireStatus(h, HandoffStatus.ACKNOWLEDGED);
        h.complete(result);
        handoffs.flush();
        tasks.recordHandoffEvent(actor, h.getTaskId(), "handoff_completed", Json.map("handoffId", h.getId()));
        audit.record(actor, "handoff.completed", "handoff", h.getId(), Map.of());
        publisher.publishEvent(new HandoffFinishedEvent(h.getTenantId(), h.getId(), h.getStatus(), result, null));
        return HandoffView.of(h);
    }

    @Transactional
    public HandoffView fail(Actor actor, UUID id, String reason, Map<String, Object> result) {
        Handoff h = lockForReceiver(actor, id);
        requireStatus(h, HandoffStatus.ACKNOWLEDGED);
        h.fail(reason, result);
        handoffs.flush();
        tasks.recordHandoffEvent(actor, h.getTaskId(), "handoff_failed",
                Json.map("handoffId", h.getId(), "reason", reason, "status", h.getStatus()));
        audit.record(actor, "handoff.failed", "handoff", h.getId(), Json.map("reason", reason, "status", h.getStatus()));
        publisher.publishEvent(new HandoffFinishedEvent(h.getTenantId(), h.getId(), h.getStatus(), result, reason));
        return HandoffView.of(h);
    }

    @Transactional
    public HandoffView reject(Actor actor, UUID id, String reason) {
        Handoff h = lockForReceiver(actor, id);
        if (!h.getStatus().isActive()) {
            throw invalidState(h, "PENDING or ACKNOWLEDGED");
        }
        h.reject(reason);
        handoffs.flush();
        tasks.recordHandoffEvent(actor, h.getTaskId(), "handoff_rejected", Json.map("handoffId", h.getId(), "reason", reason));
        audit.record(actor, "handoff.rejected", "handoff", h.getId(), Json.map("reason", reason));
        publisher.publishEvent(new HandoffFinishedEvent(h.getTenantId(), h.getId(), h.getStatus(), null, reason));
        return HandoffView.of(h);
    }

    /** Puts a FAILED handoff back in the receiver's inbox. Allowed for the sender, the receiver and admins. */
    public HandoffView retry(Actor actor, UUID id) {
        try {
            return tx.execute(s -> {
                Handoff h = handoffs.lock(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Handoff", id));
                if (!canSee(actor, h)) {
                    throw ApiException.notFound("Handoff", id);
                }
                requireStatus(h, HandoffStatus.FAILED);
                h.requeue(null);
                handoffs.flush();
                tasks.recordHandoffEvent(actor, h.getTaskId(), "handoff_retried",
                        Json.map("handoffId", h.getId(), "attempts", h.getAttempts()));
                audit.record(actor, "handoff.retried", "handoff", h.getId(), Json.map("attempts", h.getAttempts()));
                return HandoffView.of(h);
            });
        } catch (DataIntegrityViolationException e) {
            Handoff h = tx.execute(s -> handoffs.findByTenantIdAndId(actor.tenantId(), id).orElseThrow());
            throw activeHandoffConflict(e, h.getTaskId());
        }
    }

    /**
     * Returns handoffs whose lease expired (the claiming agent crashed or hung) to PENDING, or to
     * DEAD_LETTER when attempts are exhausted. Runs in system scope across tenants; each row keeps its tenant.
     */
    @Transactional
    public List<HandoffView> recoverExpiredLeases(Instant now, int batchSize) {
        List<Handoff> expired = handoffs.findExpiredLeases(now, PageRequest.of(0, batchSize));
        for (Handoff h : expired) {
            h.requeue("Lease expired at " + h.getLeaseExpiresAt() + " without completion");
            Actor system = Actor.system(h.getTenantId());
            tasks.recordHandoffEvent(system, h.getTaskId(), "handoff_lease_expired",
                    Json.map("handoffId", h.getId(), "status", h.getStatus()));
            audit.record(system, "handoff.lease_expired", "handoff", h.getId(), Json.map("status", h.getStatus()));
        }
        handoffs.flush();
        return expired.stream().map(HandoffView::of).toList();
    }

    @Transactional(readOnly = true)
    public Map<HandoffStatus, Long> countByStatus(Actor actor) {
        Map<HandoffStatus, Long> counts = new EnumMap<>(HandoffStatus.class);
        for (HandoffStatus s : HandoffStatus.values()) {
            counts.put(s, 0L);
        }
        for (Object[] row : handoffs.countByStatus(actor.tenantId())) {
            counts.put((HandoffStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    private Handoff requireVisible(Actor actor, UUID id) {
        Handoff h = handoffs.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Handoff", id));
        if (!canSee(actor, h)) {
            throw ApiException.notFound("Handoff", id);
        }
        return h;
    }

    /** Only the receiving agent (or a user acting for the tenant, e.g. from the dashboard) may move a handoff forward. */
    private Handoff lockForReceiver(Actor actor, UUID id) {
        Handoff h = handoffs.lock(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Handoff", id));
        if (actor.isAgent() && !actor.agentId().equals(h.getToAgentId())) {
            if (actor.agentId().equals(h.getFromAgentId())) {
                throw ApiException.forbidden("Only the receiving agent can act on this handoff");
            }
            throw ApiException.notFound("Handoff", id);
        }
        return h;
    }

    /** Agents see handoffs they sent or received; users see the whole tenant's. */
    private static boolean canSee(Actor actor, Handoff h) {
        return !actor.isAgent() || actor.agentId().equals(h.getToAgentId()) || actor.agentId().equals(h.getFromAgentId());
    }

    private static void requireStatus(Handoff h, HandoffStatus expected) {
        if (h.getStatus() != expected) {
            throw invalidState(h, expected.name());
        }
    }

    private static ApiException invalidState(Handoff h, String expected) {
        return ApiException.conflict("invalid_handoff_state",
                "Handoff " + h.getId() + " is " + h.getStatus() + " (expected " + expected + ")",
                Map.of("status", h.getStatus(), "version", h.getVersion()));
    }

    private static RuntimeException activeHandoffConflict(DataIntegrityViolationException e, UUID taskId) {
        if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_handoffs_active_per_task")) {
            return ApiException.conflict("active_handoff_exists",
                    "Task " + taskId + " already has a pending or acknowledged handoff; complete, fail or reject it first",
                    Map.of("taskId", taskId));
        }
        return e;
    }

    private UUID resolveSender(Actor actor, UUID requested) {
        if (actor.isAgent()) {
            if (requested != null && !requested.equals(actor.agentId())) {
                throw ApiException.forbidden("Agents can only send handoffs as themselves");
            }
            return actor.agentId();
        }
        if (requested == null) {
            throw ApiException.badRequest("fromAgentId is required when a user creates a handoff");
        }
        return requested;
    }
}
