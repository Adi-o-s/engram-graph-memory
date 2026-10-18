package com.memora.orchestration.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.agent.Agent;
import com.memora.agent.AgentRepository;
import com.memora.agent.AgentRole;
import com.memora.agent.AgentService;
import com.memora.common.Json;
import com.memora.extraction.SensitiveContentGuard;
import com.memora.handoff.HandoffDtos.CreateHandoffRequest;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.handoff.HandoffService;
import com.memora.handoff.HandoffStatus;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryScope;
import com.memora.memory.MemoryService;
import com.memora.memory.MemoryType;
import com.memora.memory.SourceType;
import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.security.Actor;
import com.memora.security.TenantContext;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskDtos.UpdateTaskRequest;
import com.memora.task.TaskService;
import com.memora.task.TaskStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reflects workflow execution in the existing coordination model: each step's task on the board moves
 * TODO → IN_PROGRESS → DONE / FAILED / BLOCKED, and each dispatched step gets a real handoff from the
 * upstream step's agent (or the "Workflow Engine" principal) that is acknowledged, completed or failed as
 * the step progresses. Each action runs in its own transaction after the engine's commit and is retried on
 * conflicts; a failure here is logged as a run event but never changes a step's recorded result.
 */
@Component
public class CoordinationMirror {

    public static final String ENGINE_AGENT = "Workflow Engine";
    private static final Logger log = LoggerFactory.getLogger(CoordinationMirror.class);

    private final StepRunRepository steps;
    private final WorkflowRunRepository runs;
    private final TaskService tasks;
    private final HandoffService handoffs;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final MemoryService memories;
    private final EventLog events;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public CoordinationMirror(StepRunRepository steps, WorkflowRunRepository runs, TaskService tasks, HandoffService handoffs,
                              AgentRepository agentRepository, AgentService agentService, MemoryService memories,
                              EventLog events, ObjectMapper objectMapper, JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.steps = steps;
        this.runs = runs;
        this.tasks = tasks;
        this.handoffs = handoffs;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.memories = memories;
        this.events = events;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    void apply(Mirror m) {
        if (m == null || m.isEmpty()) {
            return;
        }
        for (Mirror.Action a : m.actions()) {
            TenantContext.runAsTenant(a.tenantId(), () -> applyWithRetry(a));
        }
    }

    private void applyWithRetry(Mirror.Action a) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                tx.executeWithoutResult(s -> applyOne(a));
                return;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        log.warn("Coordination update {} for run {} failed: {}", a.kind(), a.runId(), last.getMessage());
        try {
            events.append(a.tenantId(), a.runId(), a.stepRunId(), "coordination_warning",
                    "Could not update the task board or handoff (" + a.kind() + "): " + last.getMessage(), Map.of());
        } catch (RuntimeException ignored) {
            // the warning itself is best effort
        }
    }

    private void applyOne(Mirror.Action a) {
        WorkflowRun run = runs.findByTenantIdAndId(a.tenantId(), a.runId()).orElse(null);
        if (run == null) {
            return;
        }
        Actor system = Actor.system(a.tenantId());
        if (a.kind() == Mirror.Kind.RUN_STARTED) {
            moveTask(system, run.getTaskId(), TaskStatus.IN_PROGRESS, "Workflow run started", Map.of());
            return;
        }
        if (a.kind() == Mirror.Kind.RUN_FINISHED) {
            runFinished(run, system);
            return;
        }
        StepRun step = steps.findByTenantIdAndId(a.tenantId(), a.stepRunId()).orElse(null);
        if (step == null) {
            return;
        }
        AgentSnapshot agent = objectMapper.convertValue(run.getAgents().get(step.getAgentKey()), AgentSnapshot.class);
        Actor receiver = Actor.agent(a.tenantId(), agent.agentId(), agent.agentName());
        switch (a.kind()) {
            case DISPATCH -> {
                if (step.getStatus() == StepStatus.READY || step.getStatus() == StepStatus.RUNNING) {
                    ensureHandoff(run, step, agent);
                }
            }
            case STARTED -> {
                moveTask(receiver, step.getTaskId(), TaskStatus.IN_PROGRESS,
                        "Attempt " + step.getAttempts() + " started", Map.of("workflowStepStatus", "RUNNING"));
                HandoffView h = ensureHandoff(run, step, agent);
                if (!a.external() && h.status() == HandoffStatus.PENDING) {
                    handoffs.acknowledge(receiver, h.id());
                }
            }
            case ATTEMPT_FAILED -> current(step).ifPresent(h -> {
                HandoffView acked = h.status() == HandoffStatus.PENDING ? handoffs.acknowledge(receiver, h.id()) : h;
                if (acked.status() == HandoffStatus.ACKNOWLEDGED) {
                    handoffs.fail(receiver, h.id(), truncate(a.note()), Json.map("stepRunId", step.getId().toString(),
                            "attempt", step.getAttempts()));
                }
            });
            case BLOCKED -> moveTask(receiver, step.getTaskId(), TaskStatus.BLOCKED, truncate(a.note()),
                    Map.of("workflowStepStatus", "BLOCKED"));
            case FINISHED -> finished(step, receiver, a.note());
            default -> {
            }
        }
    }

    private void finished(StepRun step, Actor receiver, String note) {
        switch (step.getStatus()) {
            case SUCCEEDED -> {
                moveTask(receiver, step.getTaskId(), TaskStatus.DONE, "Step succeeded",
                        Json.map("workflowStepStatus", "SUCCEEDED", "outputKeys",
                                step.getOutput() == null ? List.of() : new ArrayList<>(step.getOutput().keySet())));
                current(step).ifPresent(h -> {
                    HandoffView acked = h.status() == HandoffStatus.PENDING ? handoffs.acknowledge(receiver, h.id()) : h;
                    if (acked.status() == HandoffStatus.ACKNOWLEDGED) {
                        handoffs.complete(receiver, h.id(), Json.map("stepRunId", step.getId().toString(), "outputKeys",
                                step.getOutput() == null ? List.of() : new ArrayList<>(step.getOutput().keySet())));
                    }
                });
            }
            case SKIPPED -> {
                moveTask(receiver, step.getTaskId(), TaskStatus.DONE, "Skipped: its condition was false",
                        Map.of("workflowStepStatus", "SKIPPED"));
                current(step).filter(h -> h.status().isActive()).ifPresent(h -> handoffs.reject(receiver, h.id(), "Step skipped"));
            }
            case FAILED, CANCELLED -> {
                boolean cancelled = step.getStatus() == StepStatus.CANCELLED;
                moveTask(receiver, step.getTaskId(), TaskStatus.FAILED, truncate(cancelled ? "Cancelled" : note),
                        Map.of("workflowStepStatus", step.getStatus().name()));
                current(step).ifPresent(h -> {
                    if (h.status() == HandoffStatus.ACKNOWLEDGED && !cancelled) {
                        handoffs.fail(receiver, h.id(), truncate(note == null ? "Step failed" : note), Map.of());
                    } else if (h.status().isActive()) {
                        handoffs.reject(receiver, h.id(), cancelled ? "Run cancelled" : truncate(note == null ? "Step failed" : note));
                    }
                });
            }
            default -> {
            }
        }
    }

    private void runFinished(WorkflowRun run, Actor system) {
        boolean ok = run.getStatus() == RunStatus.SUCCEEDED;
        String summary = run.getCompletion() == null ? "" : String.valueOf(run.getCompletion().getOrDefault("summary", ""));
        moveTask(system, run.getTaskId(), ok ? TaskStatus.DONE : TaskStatus.FAILED, truncate("Run " + run.getStatus() + ": " + summary),
                Json.map("workflowRunStatus", run.getStatus().name()));
        Agent engine = enginePrincipal(run.getTenantId());
        Actor engineActor = Actor.agent(run.getTenantId(), engine.getId(), engine.getName());
        // Memory refuses anything that looks like a secret; check first so a refusal cannot roll back the task update.
        String outcome = truncate("Workflow run \"" + run.getName() + "\" finished " + run.getStatus()
                + (summary.isBlank() ? "." : ": " + summary));
        if (SensitiveContentGuard.looksSensitive(outcome)) {
            return;
        }
        memories.create(engineActor, MemoryDraft.builder(MemoryType.EPISODIC, outcome)
                .category("workflow-outcome").scope(MemoryScope.PROJECT).project(run.getProjectId())
                .tags(List.of("workflow", ok ? "success" : "failure")).importance(ok ? 3 : 4)
                .metadata(Json.map("workflowRunId", run.getId().toString(), "status", run.getStatus().name()))
                .source(SourceType.SYSTEM, null, "workflow-run:" + run.getId()).build(), "wf-outcome-" + run.getId() + "-" + run.getStatus());
        for (StepRun s : steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(run.getTenantId(), run.getId())) {
            if (s.getStatus() == StepStatus.FAILED && s.getError() != null && !SensitiveContentGuard.looksSensitive(s.getError())) {
                memories.create(engineActor, MemoryDraft.builder(MemoryType.PROCEDURAL, truncate("Lesson from run \"" + run.getName()
                                + "\": step " + s.getStepKey() + " failed after " + s.getAttempts() + " attempt(s): " + s.getError()))
                        .category("lesson").scope(MemoryScope.WORKFLOW, run.getWorkflowId())
                        .tags(List.of("workflow", "lesson", s.getStepKey())).importance(4)
                        .metadata(Json.map("workflowRunId", run.getId().toString(), "stepKey", s.getStepKey()))
                        .source(SourceType.SYSTEM, null, "workflow-run:" + run.getId()).build(), "wf-lesson-" + s.getId());
            }
        }
    }

    /** Ensures the step has an active handoff to its agent, creating or re-queuing one as needed. */
    private HandoffView ensureHandoff(WorkflowRun run, StepRun step, AgentSnapshot agent) {
        Actor system = Actor.system(run.getTenantId());
        UUID existing = jdbc.queryForList("SELECT handoff_id FROM workflow_step_runs WHERE tenant_id = ? AND id = ? FOR UPDATE",
                UUID.class, run.getTenantId(), step.getId()).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (existing != null) {
            HandoffView h = handoffs.get(system, existing);
            if (h.status().isActive()) {
                return h;
            }
            if (h.status() == HandoffStatus.FAILED) {
                return handoffs.retry(system, existing);
            }
        }
        UUID from = upstreamAgent(run, step).orElse(null);
        Agent fromAgent = from == null || from.equals(agent.agentId()) ? enginePrincipal(run.getTenantId())
                : agentRepository.findByTenantIdAndId(run.getTenantId(), from).orElse(enginePrincipal(run.getTenantId()));
        Actor sender = Actor.agent(run.getTenantId(), fromAgent.getId(), fromAgent.getName());
        List<String> upstream = new ArrayList<>(step.getDependsOn());
        String summary = truncate(step.getName() + (step.getIteration() > 1 ? " (iteration " + step.getIteration() + ")" : "")
                + " is ready in workflow run \"" + run.getName() + "\"" + (upstream.isEmpty() ? "." : "; upstream steps " + upstream
                + " are done.") + " Inputs: " + (step.getInput() == null ? "resolved when the step starts" : step.getInput().keySet()));
        HandoffView h = handoffs.create(sender, new CreateHandoffRequest(step.getTaskId(), null, agent.agentId(), summary, List.of(),
                upstream.stream().map(u -> "Upstream step " + u + " completed").toList(),
                Json.map("workflowRunId", run.getId().toString(), "stepRunId", step.getId().toString(), "stepKey", step.getStepKey(),
                        "iteration", step.getIteration(), "executor", step.getExecutor()),
                truncate("external".equals(step.getExecutor())
                        ? "Do the work described by the step and complete this handoff with {\"output\": {...}}. Instructions: "
                        + agent.instructions() : "Memora executes this step with the " + step.getExecutor() + " executor"),
                null, Math.max(1, Math.min(10, step.getMaxAttempts()))), null);
        jdbc.update("UPDATE workflow_step_runs SET handoff_id = ? WHERE tenant_id = ? AND id = ?", h.id(), run.getTenantId(), step.getId());
        return h;
    }

    private Optional<HandoffView> current(StepRun step) {
        UUID id = jdbc.queryForList("SELECT handoff_id FROM workflow_step_runs WHERE tenant_id = ? AND id = ?", UUID.class,
                step.getTenantId(), step.getId()).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return id == null ? Optional.empty() : Optional.of(handoffs.get(Actor.system(step.getTenantId()), id));
    }

    /** The agent of the most recently finished upstream step. */
    private Optional<UUID> upstreamAgent(WorkflowRun run, StepRun step) {
        if (step.getDependsOn().isEmpty()) {
            return Optional.empty();
        }
        List<StepRun> all = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(run.getTenantId(), run.getId());
        return all.stream()
                .filter(s -> step.getDependsOn().stream().anyMatch(d -> d.equals(s.getStepKey()) || d.equals(s.getStepKey() + "#" + s.getIteration())))
                .filter(s -> s.getStatus() == StepStatus.SUCCEEDED && s.getFinishedAt() != null)
                .max(Comparator.comparing(StepRun::getFinishedAt))
                .map(s -> objectMapper.convertValue(run.getAgents().get(s.getAgentKey()), AgentSnapshot.class).agentId());
    }

    /** The per-tenant principal that sends handoffs for root steps. Created on first use. */
    public Agent enginePrincipal(UUID tenantId) {
        Optional<Agent> found = agentRepository.findByTenantIdAndName(tenantId, ENGINE_AGENT);
        if (found.isPresent()) {
            return found.get();
        }
        try {
            UUID id = agentService.register(Actor.system(tenantId), ENGINE_AGENT, AgentRole.CUSTOM,
                    "Memora's workflow engine: dispatches workflow steps to agents", List.of("orchestrate"), false).agent().id();
            return agentRepository.findByTenantIdAndId(tenantId, id).orElseThrow();
        } catch (DataIntegrityViolationException e) {
            return agentRepository.findByTenantIdAndName(tenantId, ENGINE_AGENT).orElseThrow(() -> e);
        }
    }

    /** Moves a task along the allowed transitions to the target status (e.g. TODO → IN_PROGRESS → DONE). */
    private void moveTask(Actor actor, UUID taskId, TaskStatus target, String note, Map<String, Object> statePatch) {
        if (taskId == null) {
            return;
        }
        TaskView t = tasks.get(actor, taskId);
        List<TaskStatus> path = path(t.status(), target);
        if (path.isEmpty()) {
            if (!statePatch.isEmpty()) {
                tasks.update(actor, taskId, new UpdateTaskRequest(t.version(), null, null, null, null, null, null, statePatch, note));
            }
            return;
        }
        for (int i = 0; i < path.size(); i++) {
            t = tasks.update(actor, taskId, new UpdateTaskRequest(t.version(), null, null, path.get(i), null, null, null,
                    i == path.size() - 1 && !statePatch.isEmpty() ? statePatch : null, i == path.size() - 1 ? note : null));
        }
    }

    static List<TaskStatus> path(TaskStatus from, TaskStatus to) {
        if (from == to) {
            return List.of();
        }
        Map<TaskStatus, TaskStatus> previous = new EnumMap<>(TaskStatus.class);
        Deque<TaskStatus> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            TaskStatus s = queue.poll();
            for (TaskStatus n : s.allowedNext()) {
                if (n != from && !previous.containsKey(n)) {
                    previous.put(n, s);
                    if (n == to) {
                        List<TaskStatus> out = new ArrayList<>();
                        for (TaskStatus c = to; c != from; c = previous.get(c)) {
                            out.add(0, c);
                        }
                        return out;
                    }
                    queue.add(n);
                }
            }
        }
        return List.of();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1900 ? s : s.substring(0, 1897) + "...";
    }
}
