package com.memora.orchestration.tool.builtin;

import static com.memora.orchestration.tool.builtin.Schemas.integer;
import static com.memora.orchestration.tool.builtin.Schemas.object;
import static com.memora.orchestration.tool.builtin.Schemas.req;
import static com.memora.orchestration.tool.builtin.Schemas.string;

import com.memora.common.Json;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.task.TaskDtos.CreateTaskRequest;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Lets an agent record follow-up work on the shared task board, in the run's project. */
public final class TaskTools {

    private TaskTools() {
    }

    @Component
    public static class CreateTaskTool implements Tool {

        private final TaskService tasks;

        public CreateTaskTool(TaskService tasks) {
            this.tasks = tasks;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("task.create", "Create task",
                    "Creates a task on the shared board in the run's project, linked to the run and step (for outstanding "
                            + "work or follow-ups found during execution).",
                    "coordination", "task.write", true, false, 5000,
                    object(req("title"), "title", string("Task title", 300), "description", string("Details", 10000),
                            "priority", integer("1 (highest) to 5", 1, 5)),
                    object(req("taskId")), List.of("Run's project only", "Idempotent per step and title"),
                    "Retryable on conflicts", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            String title = String.valueOf(args.get("title"));
            TaskView t = tasks.create(ctx.actor(), new CreateTaskRequest(ctx.projectId(), title,
                    args.get("description") == null ? null : String.valueOf(args.get("description")),
                    args.get("priority") instanceof Number n ? n.intValue() : 3, null,
                    Json.map("workflowRunId", String.valueOf(ctx.runId()), "createdByStep", ctx.stepKey())),
                    "wf-task-" + ctx.stepRunId() + "-" + Integer.toHexString(title.hashCode()));
            return Json.map("taskId", t.id().toString(), "title", t.title(), "status", t.status().name());
        }
    }
}
