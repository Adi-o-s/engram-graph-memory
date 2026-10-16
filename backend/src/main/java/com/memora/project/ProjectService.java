package com.memora.project;

import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.security.Actor;
import com.memora.security.Authz;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final AuditService audit;

    public ProjectService(ProjectRepository projects, AuditService audit) {
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ProjectView> list(Actor actor) {
        return projects.findByTenantIdOrderByName(actor.tenantId()).stream().map(ProjectView::of).toList();
    }

    @Transactional(readOnly = true)
    public ProjectView get(Actor actor, UUID id) {
        return ProjectView.of(require(actor, id));
    }

    @Transactional
    public ProjectView create(Actor actor, String slug, String name, String description) {
        Authz.requireAdmin(actor);
        if (projects.findByTenantIdAndSlug(actor.tenantId(), slug).isPresent()) {
            throw ApiException.conflict("duplicate_slug", "A project with slug " + slug + " already exists", Map.of());
        }
        Project project = projects.save(new Project(actor.tenantId(), slug, name, description));
        audit.record(actor, "project.created", "project", project.getId(), Json.map("slug", slug));
        return ProjectView.of(project);
    }

    /** Validates that an optional project reference belongs to the caller's tenant. */
    @Transactional(readOnly = true)
    public void requireInTenant(Actor actor, UUID projectId) {
        if (projectId != null && !projects.existsByTenantIdAndId(actor.tenantId(), projectId)) {
            throw ApiException.notFound("Project", projectId);
        }
    }

    Project require(Actor actor, UUID id) {
        return projects.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Project", id));
    }

    public record ProjectView(UUID id, String slug, String name, String description, java.time.Instant createdAt) {
        static ProjectView of(Project p) {
            return new ProjectView(p.getId(), p.getSlug(), p.getName(), p.getDescription(), p.getCreatedAt());
        }
    }
}
