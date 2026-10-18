package com.memora.orchestration.install;

import com.memora.demo.DemoProperties;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryScope;
import com.memora.memory.MemoryService;
import com.memora.memory.MemoryType;
import com.memora.memory.SourceType;
import com.memora.orchestration.agentdef.AgentDefinitionService;
import com.memora.orchestration.agentdef.AgentTemplates;
import com.memora.orchestration.run.CoordinationMirror;
import com.memora.orchestration.workflow.WorkflowDtos.WorkflowRequest;
import com.memora.orchestration.workflow.WorkflowService;
import com.memora.project.ProjectService;
import com.memora.project.ProjectService.ProjectView;
import com.memora.security.Actor;
import com.memora.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives every tenant (new or existing) the default agent definitions and example workflows, once. Existing
 * principals with the same name (the seeded Planner, Developer and Tester) are linked instead of duplicated.
 * Nothing existing is modified or deleted. With demo seeding enabled, a research project with sample notes
 * is added so the research workflow has recorded sources to cite.
 */
@Component
public class OrchestrationInstaller {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationInstaller.class);
    static final String RESEARCH_PROJECT = "search-evaluation";

    private final JdbcTemplate jdbc;
    private final AgentDefinitionService agents;
    private final AgentTemplates templates;
    private final WorkflowService workflows;
    private final CoordinationMirror mirror;
    private final ProjectService projects;
    private final MemoryService memories;
    private final DemoProperties demo;

    public OrchestrationInstaller(JdbcTemplate jdbc, AgentDefinitionService agents, AgentTemplates templates,
                                  WorkflowService workflows, CoordinationMirror mirror, ProjectService projects,
                                  MemoryService memories, DemoProperties demo) {
        this.jdbc = jdbc;
        this.agents = agents;
        this.templates = templates;
        this.workflows = workflows;
        this.mirror = mirror;
        this.projects = projects;
        this.memories = memories;
        this.demo = demo;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void install() {
        List<UUID> tenants = TenantContext.callAsSystem(() -> jdbc.queryForList("SELECT id FROM tenants ORDER BY created_at", UUID.class));
        for (UUID tenantId : tenants) {
            try {
                TenantContext.runAsTenant(tenantId, () -> installFor(Actor.system(tenantId)));
            } catch (RuntimeException e) {
                log.warn("Could not install orchestration defaults for tenant {}: {}", tenantId, e.getMessage());
            }
        }
    }

    /** Idempotent: installs whatever is missing. */
    public void installFor(Actor system) {
        int installedAgents = 0;
        for (String key : templates.keys()) {
            if (!agents.exists(system, key)) {
                agents.create(system, templates.find(key).orElseThrow(), key);
                installedAgents++;
            }
        }
        mirror.enginePrincipal(system.tenantId());
        int installedWorkflows = 0;
        for (DefaultWorkflows.Definition d : DefaultWorkflows.all()) {
            if (workflows.findByKey(system, d.key()).isEmpty()) {
                workflows.create(system, new WorkflowRequest(d.key(), d.name(), d.description(), null, d.spec(),
                        "Installed by Memora", null));
                installedWorkflows++;
            }
        }
        if (demo.enabled()) {
            seedResearchNotes(system);
        }
        if (installedAgents > 0 || installedWorkflows > 0) {
            log.info("Tenant {}: installed {} agent definition(s) and {} workflow(s)", system.tenantId(), installedAgents, installedWorkflows);
        }
    }

    private void seedResearchNotes(Actor system) {
        boolean exists = projects.list(system).stream().anyMatch(p -> p.slug().equals(RESEARCH_PROJECT));
        if (exists) {
            return;
        }
        ProjectView p = projects.create(system, RESEARCH_PROJECT, "Search engine evaluation",
                "Sample notes for the research-and-report workflow (demo data)");
        List<String> notes = List.of(
                "PostgreSQL full-text search handles product catalogues up to a few million rows with a GIN index and needs no extra service.",
                "Elasticsearch offers typo tolerance and faceting but adds a cluster to operate and monitor.",
                "Meilisearch is quick to set up and has typo tolerance, but it keeps its index in memory, so large catalogues need a lot of RAM.",
                "Our team already operates PostgreSQL in production and has no Elasticsearch experience.",
                "Search latency must stay under 100 ms at the 95th percentile for product search.",
                "Faceted filtering by category and price is required for product search.");
        for (String note : notes) {
            memories.create(system, MemoryDraft.builder(MemoryType.SEMANTIC, note).category("note").scope(MemoryScope.PROJECT)
                    .project(p.id()).tags(List.of("note", "source", "search")).importance(3)
                    .metadata(Map.of("demo", true)).source(SourceType.SYSTEM, null, "demo-notes").build(), null);
        }
    }
}
