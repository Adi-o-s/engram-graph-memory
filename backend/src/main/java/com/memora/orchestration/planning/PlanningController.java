package com.memora.orchestration.planning;

import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionRequest;
import com.memora.orchestration.agentdef.AgentDefinitionService;
import com.memora.orchestration.agentdef.AgentTemplates;
import com.memora.orchestration.workflow.WorkflowDtos.WorkflowRequest;
import com.memora.orchestration.workflow.WorkflowDtos.WorkflowView;
import com.memora.orchestration.workflow.WorkflowService;
import com.memora.security.Actor;
import com.memora.security.Authz;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Workflow planning", description = "Rule-based goal → editable workflow proposal (no model, nothing persisted until accepted)")
public class PlanningController {

    private final WorkflowPlanner planner;
    private final AgentDefinitionService agents;
    private final AgentTemplates templates;
    private final WorkflowService workflows;

    public PlanningController(WorkflowPlanner planner, AgentDefinitionService agents, AgentTemplates templates,
                              WorkflowService workflows) {
        this.planner = planner;
        this.agents = agents;
        this.templates = templates;
        this.workflows = workflows;
    }

    public record PlanRequest(@NotBlank @Size(max = 20000) String goal) {
    }

    public record AcceptRequest(@Size(max = 64) String key, @NotBlank @Size(max = 200) String name,
                                @Size(max = 4000) String description, UUID projectId, @NotNull Map<String, Object> spec,
                                Boolean installMissingAgents) {
    }

    public record AcceptResult(WorkflowView workflow, List<String> installedAgents) {
    }

    @PostMapping("/api/v1/workflow-plans")
    @Operation(summary = "Propose a workflow for a goal: requirements, roles, steps, dependencies, parallel groups, gaps and limitations")
    public WorkflowPlanner.Proposal plan(Actor actor, @Valid @RequestBody PlanRequest request) {
        return planner.propose(actor, request.goal());
    }

    @PostMapping("/api/v1/workflow-plans/accept")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save an (edited) proposal as a workflow, installing referenced agent templates that are missing (ADMIN)")
    @Transactional
    public AcceptResult accept(Actor actor, @Valid @RequestBody AcceptRequest request) {
        Authz.requireAdmin(actor);
        List<String> installed = new ArrayList<>();
        if (!Boolean.FALSE.equals(request.installMissingAgents()) && request.spec().get("steps") instanceof Collection<?> steps) {
            for (Object o : steps) {
                if (o instanceof Map<?, ?> s && s.get("agent") != null) {
                    String key = String.valueOf(s.get("agent"));
                    if (!agents.exists(actor, key)) {
                        Optional<AgentDefinitionRequest> t = templates.find(key);
                        if (t.isPresent()) {
                            agents.create(actor, t.get(), key);
                            installed.add(key);
                        }
                    }
                }
            }
        }
        WorkflowView w = workflows.create(actor, new WorkflowRequest(request.key(), request.name(), request.description(),
                request.projectId(), request.spec(), "Created from a plan proposal", null));
        return new AcceptResult(w, installed);
    }
}
