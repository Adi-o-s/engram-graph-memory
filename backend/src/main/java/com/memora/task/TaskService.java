package com.memora.task;

import com.memora.agent.AgentService;
import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Idempotency;
import com.memora.common.Json;
import com.memora.common.PageResponse;
import com.memora.project.ProjectService;
import com.memora.security.Actor;
import com.memora.task.TaskDtos.CreateTaskRequest;
import com.memora.task.TaskDtos.TaskEventView;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskDtos.UpdateTaskRequest;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TaskService {

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final ProjectService projects;
    private final AgentService agents;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public TaskService(TaskRepository tasks, TaskEventRepository events, ProjectService projects, AgentService agents,
                       AuditService audit, TransactionTemplate tx) {
        this.tasks = tasks;
        this.events = events;
        this.projects = projects;
        this.agents = agents;
        this.audit = audit;
        this.tx = tx;
    }

    public TaskView create(Actor actor, CreateTaskRequest request, String idempotencyKey) {
        String key = Idempotency.normalize(idempotencyKey);
        return Idempotency.createOnce(key,
                () -> tx.execute(s -> tasks.findByTenantIdAndIdempotencyKey(actor.tenantId(), key).map(TaskView::of)),
                () -> tx.execute(s -> {
                    projects.requireInTenant(actor, request.projectId());
                    if (request.assigneeAgentId() != null) {
                        agents.require(actor, request.assigneeAgentId());
                    }
                    Task task = tasks.save(new Task(actor.tenantId(), request.projectId(), request.title(),
                            request.description(), request.priority() == null ? 3 : request.priority(),
                            request.assigneeAgentId(), actor.userId(), actor.agentId(), request.state(), key));
                    tasks.flush();
                    recordEvent(actor, task, "created", null, task.getStatus(), Json.map("title", task.getTitle()));
                    audit.record(actor, "task.created", "task", task.getId(), Json.map("title", task.getTitle()));
                    return TaskView.of(task);
                }));
    }

    @Transactional(readOnly = true)
    public TaskView get(Actor actor, UUID id) {
        return TaskView.of(require(actor, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskView> list(Actor actor, UUID projectId, TaskStatus status, UUID assignee, int page, int size) {
        projects.requireInTenant(actor, projectId);
        return PageResponse.of(tasks.search(actor.tenantId(), projectId, status, assignee, PageRequest.of(page, size)),
                TaskView::of);
    }

    /**
     * Applies a version-checked update. The explicit comparison gives a precise 409 for clients holding
     * an old copy; the JPA {@code @Version} check in the UPDATE statement catches writers that race
     * between our read and our write. Either way, no successful update is ever silently lost.
     */
    @Transactional
    public TaskView update(Actor actor, UUID id, UpdateTaskRequest request) {
        Task task = require(actor, id);
        if (!task.getVersion().equals(request.expectedVersion())) {
            throw ApiException.staleVersion("Task", id, request.expectedVersion(), task.getVersion());
        }
        TaskStatus from = task.getStatus();
        Map<String, Object> changes = new HashMap<>();
        if (request.title() != null) {
            task.setTitle(request.title());
            changes.put("title", request.title());
        }
        if (request.description() != null) {
            task.setDescription(request.description());
            changes.put("description", "updated");
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
            changes.put("priority", request.priority());
        }
        if (Boolean.TRUE.equals(request.unassign())) {
            task.setAssigneeAgentId(null);
            changes.put("assignee", null);
        } else if (request.assigneeAgentId() != null) {
            agents.require(actor, request.assigneeAgentId());
            task.setAssigneeAgentId(request.assigneeAgentId());
            changes.put("assigneeAgentId", request.assigneeAgentId());
        }
        if (request.status() != null && request.status() != from) {
            if (!from.canTransitionTo(request.status())) {
                throw ApiException.conflict("invalid_transition",
                        "Task cannot move from " + from + " to " + request.status(),
                        Map.of("from", from, "to", request.status(), "allowed", from.allowedNext()));
            }
            task.setStatus(request.status());
        }
        if (request.statePatch() != null && !request.statePatch().isEmpty()) {
            Map<String, Object> merged = new HashMap<>(task.getState());
            request.statePatch().forEach((k, v) -> {
                if (v == null) {
                    merged.remove(k);
                } else {
                    merged.put(k, v);
                }
            });
            task.setState(merged);
            changes.put("stateKeys", request.statePatch().keySet());
        }
        tasks.flush();
        String eventType = request.status() != null && request.status() != from ? "status_changed" : "updated";
        Map<String, Object> payload = new HashMap<>(changes);
        if (request.note() != null) {
            payload.put("note", request.note());
        }
        recordEvent(actor, task, eventType, from, task.getStatus(), payload);
        return TaskView.of(task);
    }

    /** Used by handoffs: reassigns the task inside the handoff's transaction, checking the version when given. */
    @Transactional
    public TaskView assignForHandoff(Actor actor, UUID taskId, UUID toAgentId, Long expectedVersion, UUID handoffId) {
        Task task = require(actor, taskId);
        if (expectedVersion != null && !task.getVersion().equals(expectedVersion)) {
            throw ApiException.staleVersion("Task", taskId, expectedVersion, task.getVersion());
        }
        task.setAssigneeAgentId(toAgentId);
        tasks.flush();
        recordEvent(actor, task, "handoff_created", task.getStatus(), task.getStatus(),
                Json.map("handoffId", handoffId, "toAgentId", toAgentId));
        return TaskView.of(task);
    }

    @Transactional
    public void recordHandoffEvent(Actor actor, UUID taskId, String eventType, Map<String, Object> payload) {
        Task task = require(actor, taskId);
        recordEvent(actor, task, eventType, task.getStatus(), task.getStatus(), payload);
    }

    @Transactional(readOnly = true)
    public List<TaskEventView> events(Actor actor, UUID taskId) {
        require(actor, taskId);
        return events.findByTenantIdAndTaskIdOrderByCreatedAtAsc(actor.tenantId(), taskId).stream()
                .map(TaskEventView::of).toList();
    }

    @Transactional(readOnly = true)
    public void requireExists(Actor actor, UUID taskId) {
        if (taskId != null && !tasks.existsByTenantIdAndId(actor.tenantId(), taskId)) {
            throw ApiException.notFound("Task", taskId);
        }
    }

    @Transactional(readOnly = true)
    public Map<TaskStatus, Long> countByStatus(Actor actor) {
        Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
        for (TaskStatus s : TaskStatus.values()) {
            counts.put(s, 0L);
        }
        for (Object[] row : tasks.countByStatus(actor.tenantId())) {
            counts.put((TaskStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    Task require(Actor actor, UUID id) {
        return tasks.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Task", id));
    }

    private void recordEvent(Actor actor, Task task, String type, TaskStatus from, TaskStatus to,
                             Map<String, Object> payload) {
        events.save(new TaskEvent(actor.tenantId(), task.getId(), type, actor.type(), actor.id(), from, to,
                task.getVersion(), payload));
    }
}
