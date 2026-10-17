package com.memora.orchestration.run;

import com.memora.common.PageResponse;
import com.memora.orchestration.run.ArtifactService.ArtifactView;
import com.memora.orchestration.run.EventLog.EventView;
import com.memora.orchestration.run.RunDtos.ApprovalDecision;
import com.memora.orchestration.run.RunDtos.CompleteStepRequest;
import com.memora.orchestration.run.RunDtos.ProjectWorkspace;
import com.memora.orchestration.run.RunDtos.RunSummary;
import com.memora.orchestration.run.RunDtos.RunView;
import com.memora.orchestration.run.RunDtos.StepDetail;
import com.memora.orchestration.run.RunDtos.ToolCallView;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Workflow runs", description = "Execution state, step details, artifacts, retry, approval, pause, resume and cancel")
public class RunController {

    private final RunService runs;
    private final WorkflowEngine engine;

    public RunController(RunService runs, WorkflowEngine engine) {
        this.runs = runs;
        this.engine = engine;
    }

    @GetMapping("/api/v1/workflow-runs")
    public PageResponse<RunSummary> list(Actor actor, @RequestParam(required = false) UUID projectId,
                                         @RequestParam(required = false) UUID workflowId,
                                         @RequestParam(required = false) RunStatus status,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return runs.list(actor, projectId, workflowId, status, Math.max(0, page), PageResponse.clampSize(size, 20, 100));
    }

    @GetMapping("/api/v1/workflow-runs/{id}")
    @Operation(summary = "A run with every step iteration, its status, inputs, outputs, attempts and why it became ready")
    public RunView get(Actor actor, @PathVariable UUID id) {
        return runs.get(actor, id);
    }

    @GetMapping("/api/v1/workflow-runs/{id}/events")
    public List<EventView> events(Actor actor, @PathVariable UUID id) {
        return runs.events(actor, id);
    }

    @GetMapping("/api/v1/workflow-runs/{id}/artifacts")
    public List<ArtifactView> artifacts(Actor actor, @PathVariable UUID id, @RequestParam(defaultValue = "false") boolean allVersions) {
        return runs.artifacts(actor, id, allVersions);
    }

    @GetMapping("/api/v1/workflow-runs/{id}/tool-calls")
    public List<ToolCallView> toolCalls(Actor actor, @PathVariable UUID id) {
        return runs.toolCalls(actor, id, null);
    }

    @GetMapping("/api/v1/workflow-runs/{id}/steps/{stepId}")
    @Operation(summary = "One step: inputs, outputs, log, events, tool calls and artifacts")
    public StepDetail step(Actor actor, @PathVariable UUID id, @PathVariable UUID stepId) {
        return runs.step(actor, id, stepId);
    }

    @GetMapping("/api/v1/workflow-artifacts/{artifactId}")
    public ArtifactView artifact(Actor actor, @PathVariable UUID artifactId) {
        return runs.artifact(actor, artifactId);
    }

    @PostMapping("/api/v1/workflow-runs/{id}/pause")
    @Operation(summary = "Stop starting new steps; running steps finish and are recorded")
    public RunView pause(Actor actor, @PathVariable UUID id) {
        engine.pause(actor, id);
        return runs.get(actor, id);
    }

    @PostMapping("/api/v1/workflow-runs/{id}/resume")
    @Operation(summary = "Continue a paused, blocked or failed run; completed steps are kept")
    public RunView resume(Actor actor, @PathVariable UUID id) {
        engine.resume(actor, id);
        return runs.get(actor, id);
    }

    @PostMapping("/api/v1/workflow-runs/{id}/cancel")
    public RunView cancel(Actor actor, @PathVariable UUID id) {
        engine.cancel(actor, id);
        return runs.get(actor, id);
    }

    @PostMapping("/api/v1/workflow-runs/{id}/steps/{stepId}/retry")
    @Operation(summary = "Retry a FAILED step (one more attempt); blocked downstream steps resume")
    public RunView retry(Actor actor, @PathVariable UUID id, @PathVariable UUID stepId) {
        engine.retryStep(actor, id, stepId);
        return runs.get(actor, id);
    }

    @PostMapping("/api/v1/workflow-runs/{id}/steps/{stepId}/approve")
    @Operation(summary = "Approve a step waiting for approval (ADMIN users)")
    public RunView approve(Actor actor, @PathVariable UUID id, @PathVariable UUID stepId,
                           @Valid @RequestBody(required = false) ApprovalDecision body) {
        engine.decide(actor, id, stepId, true, body == null ? null : body.note());
        return runs.get(actor, id);
    }

    @PostMapping("/api/v1/workflow-runs/{id}/steps/{stepId}/reject")
    public RunView reject(Actor actor, @PathVariable UUID id, @PathVariable UUID stepId,
                          @Valid @RequestBody(required = false) ApprovalDecision body) {
        engine.decide(actor, id, stepId, false, body == null ? null : body.note());
        return runs.get(actor, id);
    }

    @PostMapping("/api/v1/workflow-runs/{id}/steps/{stepId}/complete")
    @Operation(summary = "Report the result of an externally executed step (its agent's key, or an ADMIN user)")
    public RunView complete(Actor actor, @PathVariable UUID id, @PathVariable UUID stepId, @Valid @RequestBody CompleteStepRequest body) {
        engine.completeExternal(actor, id, stepId, body.output(), body.error(), Boolean.TRUE.equals(body.retryable()));
        return runs.get(actor, id);
    }

    @GetMapping("/api/v1/projects/{projectId}/workspace")
    @Operation(summary = "Project workspace: runs, decisions, requirements, lessons, artifacts, outstanding work and history")
    public ProjectWorkspace workspace(Actor actor, @PathVariable UUID projectId) {
        return runs.workspace(actor, projectId);
    }
}
