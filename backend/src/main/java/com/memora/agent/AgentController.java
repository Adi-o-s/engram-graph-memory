package com.memora.agent;

import com.memora.agent.AgentService.AgentView;
import com.memora.agent.AgentService.RegisteredAgent;
import com.memora.common.ApiException;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.handoff.HandoffService;
import com.memora.handoff.HandoffStatus;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents")
@Tag(name = "Agents", description = "Agent registry, capabilities and inboxes")
public class AgentController {

    private final AgentService agentService;
    private final HandoffService handoffService;

    public AgentController(AgentService agentService, HandoffService handoffService) {
        this.agentService = agentService;
        this.handoffService = handoffService;
    }

    @GetMapping
    public List<AgentView> list(Actor actor) {
        return agentService.list(actor);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register an agent (ADMIN). The returned apiKey is shown once; send it as X-Agent-Key.")
    public RegisteredAgent register(Actor actor, @Valid @RequestBody RegisterAgentRequest request) {
        return agentService.register(actor, request.name(), request.role(), request.description(), request.capabilities(),
                request.issueApiKey() == null || request.issueApiKey());
    }

    @GetMapping("/{id}")
    public AgentView get(Actor actor, @PathVariable UUID id) {
        return agentService.get(actor, id);
    }

    @PostMapping("/{id}/rotate-key")
    @Operation(summary = "Issue a new API key for an agent (ADMIN); the old key stops working")
    public RegisteredAgent rotateKey(Actor actor, @PathVariable UUID id) {
        return agentService.rotateKey(actor, id);
    }

    @GetMapping("/me")
    @Operation(summary = "The calling agent (agent keys only)")
    public AgentView me(Actor actor) {
        if (!actor.isAgent()) {
            throw ApiException.badRequest("Only agents have an agent identity");
        }
        return agentService.get(actor, actor.agentId());
    }

    @GetMapping("/{id}/inbox")
    @Operation(summary = "Handoffs addressed to an agent (default: PENDING and ACKNOWLEDGED)")
    public List<HandoffView> inbox(Actor actor, @PathVariable UUID id,
                                   @RequestParam(required = false) Set<HandoffStatus> status) {
        return handoffService.inbox(actor, id, status);
    }

    public record RegisterAgentRequest(@NotBlank @Size(max = 100) String name, @NotNull AgentRole role,
                                       @Size(max = 1000) String description,
                                       @Size(max = 30) List<@Size(max = 60) String> capabilities,
                                       Boolean issueApiKey) {
    }
}
