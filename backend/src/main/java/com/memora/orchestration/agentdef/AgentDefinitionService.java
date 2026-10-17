package com.memora.orchestration.agentdef;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.agent.Agent;
import com.memora.agent.AgentRepository;
import com.memora.agent.AgentRole;
import com.memora.agent.AgentService;
import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionRequest;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionView;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.DuplicateRequest;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.ValidationReport;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.VersionView;
import com.memora.security.Actor;
import com.memora.security.Authz;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent definitions: create, edit (versioned), duplicate, activate/deactivate, validate. Managing agents
 * is an ADMIN action, like registering an agent principal; any tenant user can read and validate them.
 */
@Service
public class AgentDefinitionService {

    private final AgentDefinitionRepository definitions;
    private final AgentDefinitionVersionRepository versions;
    private final AgentRepository agentRepository;
    private final AgentService agentService;
    private final AgentDefinitionValidator validator;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public AgentDefinitionService(AgentDefinitionRepository definitions, AgentDefinitionVersionRepository versions,
                                  AgentRepository agentRepository, AgentService agentService,
                                  AgentDefinitionValidator validator, AuditService audit, ObjectMapper objectMapper) {
        this.definitions = definitions;
        this.versions = versions;
        this.agentRepository = agentRepository;
        this.agentService = agentService;
        this.validator = validator;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AgentDefinitionView> list(Actor actor) {
        Map<UUID, Agent> principals = principals(actor);
        return definitions.findByTenantIdOrderByName(actor.tenantId()).stream()
                .map(d -> view(d, principals.get(d.getAgentId()))).toList();
    }

    @Transactional(readOnly = true)
    public AgentDefinitionView get(Actor actor, UUID id) {
        AgentDefinition d = require(actor, id);
        return view(d, agentRepository.findByTenantIdAndId(actor.tenantId(), d.getAgentId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(Actor actor, UUID id) {
        require(actor, id);
        return versions.findByTenantIdAndDefinitionIdOrderByVersionDesc(actor.tenantId(), id).stream()
                .map(v -> new VersionView(v.getVersion(), v.getSnapshot(), v.getCreatedByUserId(), v.getCreatedAt())).toList();
    }

    public ValidationReport validate(AgentDefinitionRequest request) {
        return validator.validate(request, keyFor(request));
    }

    @Transactional
    public AgentDefinitionView create(Actor actor, AgentDefinitionRequest request, String sourceTemplate) {
        Authz.requireAdmin(actor);
        String key = keyFor(request);
        requireValid(validator.validate(request, key));
        if (definitions.existsByTenantIdAndKey(actor.tenantId(), key)) {
            throw ApiException.conflict("duplicate_agent_definition", "An agent definition with key " + key + " already exists",
                    Map.of("key", key));
        }
        Agent principal = principalFor(actor, request);
        AgentDefinition d = new AgentDefinition(actor.tenantId(), key, principal.getId(), actor.userId(), sourceTemplate);
        d.apply(request);
        definitions.save(d);
        definitions.flush();
        saveVersion(actor, d, principal.getName());
        audit.record(actor, "agent_definition.created", "agent_definition", d.getId(),
                Json.map("key", key, "executor", d.getExecutor(), "template", sourceTemplate));
        return view(d, principal);
    }

    @Transactional
    public AgentDefinitionView update(Actor actor, UUID id, AgentDefinitionRequest request) {
        Authz.requireAdmin(actor);
        AgentDefinition d = require(actor, id);
        if (request.expectedLockVersion() == null) {
            throw ApiException.badRequest("expectedLockVersion is required when updating an agent definition");
        }
        if (!d.getLockVersion().equals(request.expectedLockVersion())) {
            throw ApiException.staleVersion("Agent definition", id, request.expectedLockVersion(), d.getLockVersion());
        }
        if (request.key() != null && !request.key().equals(d.getKey())) {
            throw ApiException.badRequest("The key of an agent definition cannot change (workflows refer to it)");
        }
        requireValid(validator.validate(request, d.getKey()));
        Agent principal = agentRepository.findByTenantIdAndId(actor.tenantId(), d.getAgentId())
                .orElseThrow(() -> ApiException.notFound("Agent", d.getAgentId()));
        String newName = request.name().trim();
        if (!newName.equals(principal.getName())) {
            if (agentRepository.findByTenantIdAndName(actor.tenantId(), newName).isPresent()) {
                throw ApiException.conflict("duplicate_agent", "An agent named " + newName + " already exists", Map.of());
            }
            principal.rename(newName);
        }
        d.apply(request);
        d.bumpRevision();
        definitions.flush();
        saveVersion(actor, d, principal.getName());
        audit.record(actor, "agent_definition.updated", "agent_definition", d.getId(), Json.map("version", d.getRevision()));
        return view(d, principal);
    }

    @Transactional
    public AgentDefinitionView duplicate(Actor actor, UUID id, DuplicateRequest request) {
        Authz.requireAdmin(actor);
        AgentDefinition source = require(actor, id);
        String name = request != null && request.name() != null && !request.name().isBlank() ? request.name().trim() : null;
        String key = request != null && request.key() != null && !request.key().isBlank() ? request.key().trim() : null;
        for (int n = 1; name == null || key == null; n++) {
            String candidateKey = source.getKey() + "-copy" + (n == 1 ? "" : "-" + n);
            String candidateName = source.getName() + " (copy" + (n == 1 ? "" : " " + n) + ")";
            if (!definitions.existsByTenantIdAndKey(actor.tenantId(), candidateKey)
                    && agentRepository.findByTenantIdAndName(actor.tenantId(), candidateName).isEmpty()) {
                key = key == null ? candidateKey : key;
                name = name == null ? candidateName : name;
            }
            if (n > 50) {
                throw ApiException.conflict("duplicate_agent_definition", "Could not find a free name for the copy", Map.of());
            }
        }
        AgentDefinitionRequest copy = new AgentDefinitionRequest(key, name, source.getDescription(), source.getRole(),
                source.getInstructions(), source.getCapabilities(), source.getTools(), source.getInputSchema(),
                source.getOutputSchema(), source.getMemoryPolicy(), source.getContextPolicy(), source.getExecutor(),
                source.getBehavior(), source.getTimeoutSeconds(), source.getMaxAttempts(), source.getBackoffSeconds(), null);
        return create(actor, copy, source.getSourceTemplate());
    }

    @Transactional
    public AgentDefinitionView setActive(Actor actor, UUID id, boolean active) {
        Authz.requireAdmin(actor);
        AgentDefinition d = require(actor, id);
        d.setStatus(active ? "ACTIVE" : "INACTIVE");
        definitions.flush();
        audit.record(actor, active ? "agent_definition.activated" : "agent_definition.deactivated", "agent_definition",
                d.getId(), Map.of());
        return get(actor, id);
    }

    /** Snapshots for a run. Every key must exist and be ACTIVE; otherwise the run is refused. */
    @Transactional(readOnly = true)
    public Map<String, AgentSnapshot> snapshots(Actor actor, Collection<String> keys) {
        Map<String, AgentDefinition> found = definitions.findByTenantIdAndKeyIn(actor.tenantId(), keys).stream()
                .collect(Collectors.toMap(AgentDefinition::getKey, Function.identity()));
        Map<UUID, Agent> principals = principals(actor);
        Map<String, AgentSnapshot> out = new LinkedHashMap<>();
        for (String key : keys) {
            AgentDefinition d = found.get(key);
            if (d == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_agent", "No agent definition with key " + key,
                        Map.of("agent", key));
            }
            if (!"ACTIVE".equals(d.getStatus())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "inactive_agent",
                        "Agent " + key + " is inactive; activate it before running a workflow that uses it", Map.of("agent", key));
            }
            Agent principal = principals.get(d.getAgentId());
            out.put(key, d.snapshot(principal == null ? d.getName() : principal.getName()));
        }
        return out;
    }

    /** Key → status for every definition in the tenant (workflow validation). */
    @Transactional(readOnly = true)
    public Map<String, String> statusesByKey(Actor actor) {
        return definitions.findByTenantIdOrderByName(actor.tenantId()).stream()
                .collect(Collectors.toMap(AgentDefinition::getKey, AgentDefinition::getStatus, (a, b) -> a, LinkedHashMap::new));
    }

    @Transactional(readOnly = true)
    public Map<String, AgentSnapshot> allSnapshots(Actor actor) {
        Map<UUID, Agent> principals = principals(actor);
        Map<String, AgentSnapshot> out = new LinkedHashMap<>();
        for (AgentDefinition d : definitions.findByTenantIdOrderByName(actor.tenantId())) {
            Agent p = principals.get(d.getAgentId());
            out.put(d.getKey(), d.snapshot(p == null ? d.getName() : p.getName()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public boolean exists(Actor actor, String key) {
        return definitions.existsByTenantIdAndKey(actor.tenantId(), key);
    }

    @Transactional(readOnly = true)
    public long count(Actor actor) {
        return definitions.countByTenantId(actor.tenantId());
    }

    /** Reuses an existing principal with the same name (e.g. the seeded Planner) unless another definition owns it. */
    private Agent principalFor(Actor actor, AgentDefinitionRequest request) {
        String name = request.name().trim();
        var existing = agentRepository.findByTenantIdAndName(actor.tenantId(), name);
        if (existing.isPresent()) {
            if (definitions.findByTenantIdAndAgentId(actor.tenantId(), existing.get().getId()).isPresent()) {
                throw ApiException.conflict("duplicate_agent", "An agent named " + name + " already exists", Map.of("name", name));
            }
            return existing.get();
        }
        UUID id = agentService.register(actor, name, AgentRole.CUSTOM, request.description(),
                request.capabilities(), false).agent().id();
        return agentRepository.findByTenantIdAndId(actor.tenantId(), id).orElseThrow();
    }

    private void saveVersion(Actor actor, AgentDefinition d, String agentName) {
        Map<String, Object> snapshot = objectMapper.convertValue(d.snapshot(agentName), new TypeReference<>() {
        });
        versions.save(new AgentDefinitionVersion(actor.tenantId(), d.getId(), d.getRevision(), snapshot, actor.userId()));
    }

    private static void requireValid(ValidationReport report) {
        if (!report.valid()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_agent_definition",
                    "The agent definition is not valid: " + report.errors().get(0).field() + " " + report.errors().get(0).message(),
                    Map.of("errors", report.errors(), "warnings", report.warnings()));
        }
    }

    AgentDefinition require(Actor actor, UUID id) {
        return definitions.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Agent definition", id));
    }

    private Map<UUID, Agent> principals(Actor actor) {
        return agentRepository.findByTenantIdOrderByName(actor.tenantId()).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity()));
    }

    private static AgentDefinitionView view(AgentDefinition d, Agent principal) {
        return AgentDefinitionView.of(d, principal == null ? null : principal.getName(),
                principal != null && principal.getApiKeyPrefix() != null);
    }

    static String keyFor(AgentDefinitionRequest r) {
        if (r.key() != null && !r.key().isBlank()) {
            return r.key().trim();
        }
        String slug = (r.name() == null ? "" : r.name()).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            slug = "agent-" + slug;
        }
        return slug.length() > 64 ? slug.substring(0, 64).replaceAll("-+$", "") : slug;
    }
}
