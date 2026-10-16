package com.memora.agent;

import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.security.Actor;
import com.memora.security.ApiKeys;
import com.memora.security.Authz;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {

    private final AgentRepository agents;
    private final AuditService audit;

    public AgentService(AgentRepository agents, AuditService audit) {
        this.agents = agents;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<AgentView> list(Actor actor) {
        return agents.findByTenantIdOrderByName(actor.tenantId()).stream().map(AgentView::of).toList();
    }

    @Transactional(readOnly = true)
    public AgentView get(Actor actor, UUID id) {
        return AgentView.of(require(actor, id));
    }

    /** Registers an agent and returns its API key. The key is shown once and only its hash is kept. */
    @Transactional
    public RegisteredAgent register(Actor actor, String name, AgentRole role, String description, List<String> capabilities,
                                    boolean issueKey) {
        Authz.requireAdmin(actor);
        if (agents.findByTenantIdAndName(actor.tenantId(), name).isPresent()) {
            throw ApiException.conflict("duplicate_agent", "An agent named " + name + " already exists", Map.of());
        }
        Agent agent = new Agent(actor.tenantId(), name, role, description,
                capabilities == null ? new String[0] : capabilities.stream().map(String::trim).distinct().toArray(String[]::new));
        String key = null;
        if (issueKey) {
            key = ApiKeys.generate();
            agent.setApiKey(ApiKeys.hash(key), ApiKeys.prefix(key));
        }
        agents.save(agent);
        audit.record(actor, "agent.registered", "agent", agent.getId(), Json.map("name", name, "role", role.name()));
        return new RegisteredAgent(AgentView.of(agent), key);
    }

    /** Issues a new API key, invalidating the previous one. */
    @Transactional
    public RegisteredAgent rotateKey(Actor actor, UUID id) {
        Authz.requireAdmin(actor);
        Agent agent = require(actor, id);
        String key = ApiKeys.generate();
        agent.setApiKey(ApiKeys.hash(key), ApiKeys.prefix(key));
        audit.record(actor, "agent.key_rotated", "agent", agent.getId(), Json.map("prefix", ApiKeys.prefix(key)));
        return new RegisteredAgent(AgentView.of(agent), key);
    }

    @Transactional(readOnly = true)
    public Agent require(Actor actor, UUID id) {
        return agents.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Agent", id));
    }

    @Transactional(readOnly = true)
    public Agent requireByRole(Actor actor, AgentRole role) {
        return agents.findFirstByTenantIdAndRoleOrderByCreatedAt(actor.tenantId(), role)
                .orElseThrow(() -> ApiException.badRequest("No " + role + " agent is registered in this tenant"));
    }

    public record AgentView(UUID id, String name, AgentRole role, String description, List<String> capabilities,
                            String status, String apiKeyPrefix, long handoffsReceived, long handoffsSent,
                            Instant lastSeenAt, Instant createdAt) {
        static AgentView of(Agent a) {
            return new AgentView(a.getId(), a.getName(), a.getRole(), a.getDescription(),
                    Arrays.asList(a.getCapabilities()), a.getStatus(), a.getApiKeyPrefix(), a.getHandoffsReceived(),
                    a.getHandoffsSent(), a.getLastSeenAt(), a.getCreatedAt());
        }
    }

    public record RegisteredAgent(AgentView agent, String apiKey) {
    }
}
