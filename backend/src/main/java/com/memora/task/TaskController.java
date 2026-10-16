package com.memora.task;

import com.memora.common.PageResponse;
import com.memora.security.Actor;
import com.memora.task.TaskDtos.CreateTaskRequest;
import com.memora.task.TaskDtos.TaskEventView;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskDtos.UpdateTaskRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Shared, versioned task state")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a task (send Idempotency-Key to make retries safe)")
    public TaskView create(Actor actor, @Valid @RequestBody CreateTaskRequest request,
                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return taskService.create(actor, request, idempotencyKey);
    }

    @GetMapping
    public PageResponse<TaskView> list(Actor actor, @RequestParam(required = false) UUID projectId,
                                       @RequestParam(required = false) TaskStatus status,
                                       @RequestParam(required = false) UUID assigneeAgentId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(required = false) Integer size) {
        return taskService.list(actor, projectId, status, assigneeAgentId, Math.max(page, 0),
                PageResponse.clampSize(size, 20, 100));
    }

    @GetMapping("/{id}")
    public TaskView get(Actor actor, @PathVariable UUID id) {
        return taskService.get(actor, id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Version-aware update; returns 409 stale_version if expectedVersion is out of date")
    public TaskView update(Actor actor, @PathVariable UUID id, @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(actor, id, request);
    }

    @GetMapping("/{id}/events")
    public List<TaskEventView> events(Actor actor, @PathVariable UUID id) {
        return taskService.events(actor, id);
    }
}
