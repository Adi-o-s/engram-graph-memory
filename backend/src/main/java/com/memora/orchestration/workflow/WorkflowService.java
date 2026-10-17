package com.memora.orchestration.workflow;

import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.orchestration.agentdef.AgentDefinitionService;
import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.orchestration.workflow.WorkflowDtos.DuplicateRequest;
import com.memora.orchestration.workflow.WorkflowDtos.ValidationView;
import com.memora.orchestration.workflow.WorkflowDtos.VersionView;
import com.memora.orchestration.workflow.WorkflowDtos.WorkflowRequest;
import com.memora.orchestration.workflow.WorkflowDtos.WorkflowView;
import com.memora.project.ProjectService;
import com.memora.security.Actor;
import com.memora.security.Authz;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workflow definitions: validated, versioned specs. Editing never changes a version that runs used. */
@Service
public class WorkflowService {

    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{1,63}");

    private final WorkflowDefinitionRepository workflows;
    private final WorkflowVersionRepository versions;
    private final WorkflowValidator validator;
    private final AgentDefinitionService agents;
    private final ProjectService projects;
    private final AuditService audit;

    public WorkflowService(WorkflowDefinitionRepository workflows, WorkflowVersionRepository versions,
                           WorkflowValidator validator, AgentDefinitionService agents, ProjectService projects,
                           AuditService audit) {
        this.workflows = workflows;
        this.versions = versions;
        this.validator = validator;
        this.agents = agents;
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<WorkflowView> list(Actor actor) {
        Map<String, AgentSnapshot> snapshots = agents.allSnapshots(actor);
        Map<String, String> statuses = agents.statusesByKey(actor);
        return workflows.findByTenantIdOrderByName(actor.tenantId()).stream()
                .map(w -> view(actor, w, snapshots, statuses)).toList();
    }

    @Transactional(readOnly = true)
    public WorkflowView get(Actor actor, UUID id) {
        return view(actor, require(actor, id), agents.allSnapshots(actor), agents.statusesByKey(actor));
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(Actor actor, UUID id) {
        require(actor, id);
        return versions.findByTenantIdAndWorkflowIdOrderByVersionDesc(actor.tenantId(), id).stream()
                .map(v -> new VersionView(v.getVersion(), v.getSpec(), v.getChangeNote(), v.getCreatedByUserId(), v.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ValidationView validate(Actor actor, Map<String, Object> spec) {
        return ValidationView.of(validator.validate(spec, agents.allSnapshots(actor), agents.statusesByKey(actor), false));
    }

    @Transactional
    public WorkflowView create(Actor actor, WorkflowRequest request) {
        Authz.requireAdmin(actor);
        String key = keyFor(request.key(), request.name());
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("key must be 2-64 characters: lowercase letters, digits and hyphens");
        }
        if (workflows.existsByTenantIdAndKey(actor.tenantId(), key)) {
            throw ApiException.conflict("duplicate_workflow", "A workflow with key " + key + " already exists", Map.of("key", key));
        }
        projects.requireInTenant(actor, request.projectId());
        requireValid(actor, request.spec());
        WorkflowDefinition w = new WorkflowDefinition(actor.tenantId(), key, request.name().trim(), request.description(),
                request.projectId(), actor.userId());
        workflows.save(w);
        workflows.flush();
        versions.save(new WorkflowDefinitionVersion(actor.tenantId(), w.getId(), 1, request.spec(),
                request.changeNote() == null ? "Created" : request.changeNote(), actor.userId()));
        audit.record(actor, "workflow.created", "workflow", w.getId(), Json.map("key", key));
        return get(actor, w.getId());
    }

    @Transactional
    public WorkflowView update(Actor actor, UUID id, WorkflowRequest request) {
        Authz.requireAdmin(actor);
        WorkflowDefinition w = require(actor, id);
        if (request.expectedLockVersion() == null) {
            throw ApiException.badRequest("expectedLockVersion is required when updating a workflow");
        }
        if (!w.getLockVersion().equals(request.expectedLockVersion())) {
            throw ApiException.staleVersion("Workflow", id, request.expectedLockVersion(), w.getLockVersion());
        }
        if (request.key() != null && !request.key().equals(w.getKey())) {
            throw ApiException.badRequest("The key of a workflow cannot change");
        }
        projects.requireInTenant(actor, request.projectId());
        requireValid(actor, request.spec());
        w.update(request.name().trim(), request.description(), request.projectId());
        w.newVersion();
        workflows.flush();
        versions.save(new WorkflowDefinitionVersion(actor.tenantId(), w.getId(), w.getCurrentVersion(), request.spec(),
                request.changeNote(), actor.userId()));
        audit.record(actor, "workflow.updated", "workflow", w.getId(), Json.map("version", w.getCurrentVersion()));
        return get(actor, w.getId());
    }

    @Transactional
    public WorkflowView duplicate(Actor actor, UUID id, DuplicateRequest request) {
        Authz.requireAdmin(actor);
        WorkflowDefinition source = require(actor, id);
        Map<String, Object> spec = spec(actor, id, source.getCurrentVersion());
        String name = request != null && request.name() != null && !request.name().isBlank() ? request.name() : source.getName() + " (copy)";
        String key = request != null && request.key() != null && !request.key().isBlank() ? request.key() : null;
        for (int n = 1; key == null; n++) {
            String candidate = source.getKey() + "-copy" + (n == 1 ? "" : "-" + n);
            if (!workflows.existsByTenantIdAndKey(actor.tenantId(), candidate)) {
                key = candidate;
            }
        }
        return create(actor, new WorkflowRequest(key, name, source.getDescription(), source.getProjectId(), spec,
                "Duplicated from " + source.getKey() + " v" + source.getCurrentVersion(), null));
    }

    @Transactional
    public WorkflowView setArchived(Actor actor, UUID id, boolean archived) {
        Authz.requireAdmin(actor);
        WorkflowDefinition w = require(actor, id);
        w.setStatus(archived ? "ARCHIVED" : "ACTIVE");
        workflows.flush();
        audit.record(actor, archived ? "workflow.archived" : "workflow.restored", "workflow", id, Map.of());
        return get(actor, id);
    }

    /** The spec of one version (for runs). */
    @Transactional(readOnly = true)
    public Map<String, Object> spec(Actor actor, UUID workflowId, int version) {
        return versions.findByTenantIdAndWorkflowIdAndVersion(actor.tenantId(), workflowId, version)
                .orElseThrow(() -> ApiException.notFound("Workflow version", workflowId + "@" + version)).getSpec();
    }

    @Transactional(readOnly = true)
    public WorkflowDefinition require(Actor actor, UUID id) {
        return workflows.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Workflow", id));
    }

    @Transactional(readOnly = true)
    public java.util.Optional<WorkflowDefinition> findByKey(Actor actor, String key) {
        return workflows.findByTenantIdAndKey(actor.tenantId(), key);
    }

    private void requireValid(Actor actor, Map<String, Object> spec) {
        WorkflowValidator.Result r = validator.validate(spec, agents.allSnapshots(actor), agents.statusesByKey(actor), false);
        if (!r.valid()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_workflow",
                    "The workflow is not valid: " + r.errors().get(0).path() + " " + r.errors().get(0).message(),
                    Map.of("errors", r.errors(), "warnings", r.warnings()));
        }
    }

    private WorkflowView view(Actor actor, WorkflowDefinition w, Map<String, AgentSnapshot> snapshots, Map<String, String> statuses) {
        Map<String, Object> spec = versions.findByTenantIdAndWorkflowIdAndVersion(actor.tenantId(), w.getId(), w.getCurrentVersion())
                .map(WorkflowDefinitionVersion::getSpec).orElse(Map.of());
        WorkflowValidator.Result r = validator.validate(spec, snapshots, statuses, false);
        List<String> agentKeys = r.spec() == null ? List.of()
                : r.spec().stepsOrEmpty().stream().map(WorkflowSpec.StepSpec::agent).filter(java.util.Objects::nonNull).distinct().toList();
        return new WorkflowView(w.getId(), w.getKey(), w.getName(), w.getDescription(), w.getProjectId(), w.getCurrentVersion(),
                w.getStatus(), w.getLockVersion() == null ? 0 : w.getLockVersion(), spec,
                r.spec() == null ? 0 : r.spec().stepsOrEmpty().size(), agentKeys, ValidationView.of(r), w.getCreatedAt(),
                w.getUpdatedAt());
    }

    static String keyFor(String key, String name) {
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        String slug = (name == null ? "" : name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            slug = "workflow-" + slug;
        }
        return slug.length() > 64 ? slug.substring(0, 64).replaceAll("-+$", "") : slug;
    }
}
