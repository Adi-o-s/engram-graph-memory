package com.memora.orchestration.agentdef;

import com.memora.common.ApiException;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionRequest;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionView;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.DuplicateRequest;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.TemplateView;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.ValidationReport;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.VersionView;
import com.memora.orchestration.executor.ExecutorDescriptor;
import com.memora.orchestration.executor.ExecutorRegistry;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolRegistry;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Agent definitions", description = "Configurable agents: instructions, capabilities, tools, schemas, memory policy, executor")
public class AgentDefinitionController {

    private final AgentDefinitionService service;
    private final AgentTemplates templates;
    private final ToolRegistry tools;
    private final ExecutorRegistry executors;

    public AgentDefinitionController(AgentDefinitionService service, AgentTemplates templates, ToolRegistry tools,
                                     ExecutorRegistry executors) {
        this.service = service;
        this.templates = templates;
        this.tools = tools;
        this.executors = executors;
    }

    @GetMapping("/api/v1/agent-definitions")
    public List<AgentDefinitionView> list(Actor actor) {
        return service.list(actor);
    }

    @PostMapping("/api/v1/agent-definitions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an agent definition (ADMIN). Validated first; 422 with errors when invalid.")
    public AgentDefinitionView create(Actor actor, @Valid @RequestBody AgentDefinitionRequest request) {
        return service.create(actor, request, null);
    }

    @PostMapping("/api/v1/agent-definitions/validate")
    @Operation(summary = "Validate an agent definition without saving it")
    public ValidationReport validate(Actor actor, @Valid @RequestBody AgentDefinitionRequest request) {
        return service.validate(request);
    }

    @GetMapping("/api/v1/agent-definitions/{id}")
    public AgentDefinitionView get(Actor actor, @PathVariable UUID id) {
        return service.get(actor, id);
    }

    @PutMapping("/api/v1/agent-definitions/{id}")
    @Operation(summary = "Update (ADMIN). Requires expectedLockVersion; saves a new immutable version.")
    public AgentDefinitionView update(Actor actor, @PathVariable UUID id, @Valid @RequestBody AgentDefinitionRequest request) {
        return service.update(actor, id, request);
    }

    @GetMapping("/api/v1/agent-definitions/{id}/versions")
    public List<VersionView> versions(Actor actor, @PathVariable UUID id) {
        return service.versions(actor, id);
    }

    @PostMapping("/api/v1/agent-definitions/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDefinitionView duplicate(Actor actor, @PathVariable UUID id, @RequestBody(required = false) DuplicateRequest request) {
        return service.duplicate(actor, id, request);
    }

    @PostMapping("/api/v1/agent-definitions/{id}/activate")
    public AgentDefinitionView activate(Actor actor, @PathVariable UUID id) {
        return service.setActive(actor, id, true);
    }

    @PostMapping("/api/v1/agent-definitions/{id}/deactivate")
    public AgentDefinitionView deactivate(Actor actor, @PathVariable UUID id) {
        return service.setActive(actor, id, false);
    }

    @GetMapping("/api/v1/agent-templates")
    @Operation(summary = "Read-only system templates (Planner, Developer, Tester and many others) that can be installed and edited")
    public List<TemplateView> templates(Actor actor) {
        return templates.views(key -> service.exists(actor, key));
    }

    @PostMapping("/api/v1/agent-templates/{key}/install")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tenant-owned, editable agent definition from a template (ADMIN)")
    public AgentDefinitionView install(Actor actor, @PathVariable String key) {
        AgentDefinitionRequest request = templates.find(key).orElseThrow(() -> ApiException.notFound("Agent template", key));
        return service.create(actor, request, key);
    }

    @GetMapping("/api/v1/tools")
    @Operation(summary = "The tool registry: what each tool does, its schemas, required capability, side effects and approval rules")
    public List<ToolDescriptor> tools(Actor actor) {
        return tools.descriptors();
    }

    @GetMapping("/api/v1/executors")
    @Operation(summary = "Available executors, including which are deterministic and which are not shipped")
    public List<ExecutorDescriptor> executors(Actor actor) {
        return executors.descriptors();
    }
}
