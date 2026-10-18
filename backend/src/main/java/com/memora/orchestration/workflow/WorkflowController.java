package com.memora.orchestration.workflow;

import com.memora.orchestration.run.RunDtos.StartResult;
import com.memora.orchestration.run.RunDtos.StartRunRequest;
import com.memora.orchestration.run.WorkflowEngine;
import com.memora.orchestration.workflow.WorkflowDtos.DuplicateRequest;
import com.memora.orchestration.workflow.WorkflowDtos.ValidateRequest;
import com.memora.orchestration.workflow.WorkflowDtos.ValidationView;
import com.memora.orchestration.workflow.WorkflowDtos.VersionView;
import com.memora.orchestration.workflow.WorkflowDtos.WorkflowRequest;
import com.memora.orchestration.workflow.WorkflowDtos.WorkflowView;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflows")
@Tag(name = "Workflows", description = "Versioned workflow definitions: steps, dependencies, conditions, loops, approvals, completion criteria")
public class WorkflowController {

    private final WorkflowService service;
    private final WorkflowEngine engine;

    public WorkflowController(WorkflowService service, WorkflowEngine engine) {
        this.service = service;
        this.engine = engine;
    }

    @GetMapping
    public List<WorkflowView> list(Actor actor) {
        return service.list(actor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a workflow (ADMIN). The spec is validated; 422 with errors when invalid.")
    public WorkflowView create(Actor actor, @Valid @RequestBody WorkflowRequest request) {
        return service.create(actor, request);
    }

    @PostMapping("/validate")
    @Operation(summary = "Validate a spec and get its execution layers (which steps can run in parallel)")
    public ValidationView validate(Actor actor, @Valid @RequestBody ValidateRequest request) {
        return service.validate(actor, request.spec());
    }

    @GetMapping("/{id}")
    public WorkflowView get(Actor actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Save a new version (ADMIN). Requires expectedLockVersion. Earlier versions and their runs are unchanged.")
    public WorkflowView update(Actor actor, @PathVariable UUID id, @Valid @RequestBody WorkflowRequest request) {
        return service.update(actor, id, request);
    }

    @GetMapping("/{id}/versions")
    public List<VersionView> versions(Actor actor, @PathVariable UUID id) {
        return service.versions(actor, id);
    }

    @PostMapping("/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowView duplicate(Actor actor, @PathVariable UUID id, @RequestBody(required = false) DuplicateRequest request) {
        return service.duplicate(actor, id, request);
    }

    @PostMapping("/{id}/archive")
    public WorkflowView archive(Actor actor, @PathVariable UUID id) {
        return service.setArchived(actor, id, true);
    }

    @PostMapping("/{id}/restore")
    public WorkflowView restore(Actor actor, @PathVariable UUID id) {
        return service.setArchived(actor, id, false);
    }

    @PostMapping("/{id}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a run of the current (or a given) version. Agent definitions are pinned at their current versions.")
    public StartResult start(Actor actor, @PathVariable UUID id, @Valid @RequestBody StartRunRequest request,
                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return new StartResult(engine.start(actor, id, request, key));
    }
}
