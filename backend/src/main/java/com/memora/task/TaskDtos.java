package com.memora.task;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class TaskDtos {

    private TaskDtos() {
    }

    public record CreateTaskRequest(UUID projectId, @NotBlank @Size(max = 300) String title,
                                    @Size(max = 10000) String description, @Min(1) @Max(5) Integer priority,
                                    UUID assigneeAgentId, Map<String, Object> state) {
    }

    /**
     * A version-aware update. {@code expectedVersion} is required: if the task changed since the caller
     * read it, the update is rejected with 409 instead of silently overwriting someone else's write.
     * {@code statePatch} merges top-level keys (a null value removes the key).
     */
    public record UpdateTaskRequest(@NotNull Long expectedVersion, @Size(max = 300) String title,
                                    @Size(max = 10000) String description, TaskStatus status,
                                    @Min(1) @Max(5) Integer priority, UUID assigneeAgentId, Boolean unassign,
                                    Map<String, Object> statePatch, @Size(max = 1000) String note) {
    }

    public record TaskView(UUID id, UUID projectId, String title, String description, TaskStatus status, int priority,
                           UUID assigneeAgentId, UUID createdByUserId, UUID createdByAgentId, Map<String, Object> state,
                           long version, Instant createdAt, Instant updatedAt) {
        public static TaskView of(Task t) {
            return new TaskView(t.getId(), t.getProjectId(), t.getTitle(), t.getDescription(), t.getStatus(),
                    t.getPriority(), t.getAssigneeAgentId(), t.getCreatedByUserId(), t.getCreatedByAgentId(),
                    t.getState(), t.getVersion() == null ? 0 : t.getVersion(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record TaskEventView(UUID id, String eventType, String actorType, UUID actorId, TaskStatus fromStatus,
                                TaskStatus toStatus, Long taskVersion, Map<String, Object> payload, Instant createdAt) {
        public static TaskEventView of(TaskEvent e) {
            return new TaskEventView(e.getId(), e.getEventType(), e.getActorType().name(), e.getActorId(),
                    e.getFromStatus(), e.getToStatus(), e.getTaskVersion(), e.getPayload(), e.getCreatedAt());
        }
    }
}
