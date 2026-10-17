package com.memora.orchestration.planning;

import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionRequest;
import com.memora.orchestration.agentdef.AgentDefinitionService;
import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.orchestration.agentdef.AgentTemplates;
import com.memora.orchestration.install.DefaultWorkflows;
import com.memora.orchestration.workflow.WorkflowValidator;
import com.memora.security.Actor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Rule-based planning: extract requirements, pick the closest capability template by keyword evidence
 * (software delivery, research and report, data analysis), adapt it (conditional reviews, approvals) and
 * explain the result. When no template fits, it proposes analysis plus delegation to an external agent and
 * says so. It never claims to understand a goal it only pattern-matched.
 */
@Service
public class RuleBasedWorkflowPlanner implements WorkflowPlanner {

    static final Map<String, List<String>> DOMAIN_KEYWORDS = Map.of(
            "software", List.of("website", "web app", "web application", "application", "app", "frontend", "front-end", "backend",
                    "back-end", "api", "full-stack", "full stack", "e-commerce", "ecommerce", "portal", "platform", "saas",
                    "login", "authentication", "database", "checkout", "dashboard", "screen", "deploy"),
            "research", List.of("research", "report", "compare", "comparison", "investigate", "literature", "market", "evaluate",
                    "evaluation", "study", "survey", "pros and cons", "trade-off", "recommendation", "options", "alternatives"),
            "data", List.of("csv", "dataset", "data analysis", "spreadsheet", "aggregate", "average", "sum of", "per month",
                    "group by", "statistics"));

    private final AgentDefinitionService agents;
    private final AgentTemplates templates;
    private final WorkflowValidator validator;

    public RuleBasedWorkflowPlanner(AgentDefinitionService agents, AgentTemplates templates, WorkflowValidator validator) {
        this.agents = agents;
        this.templates = templates;
        this.validator = validator;
    }

    @Override
    public Proposal propose(Actor actor, String goal) {
        if (goal == null || goal.isBlank() || goal.trim().length() < 10) {
            throw ApiException.badRequest("Describe the goal in at least a sentence");
        }
        RequirementExtractor.Result reqs = RequirementExtractor.extract(goal, 30);
        String lower = " " + goal.toLowerCase(Locale.ROOT) + " ";
        Map<String, List<String>> evidence = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : DOMAIN_KEYWORDS.entrySet()) {
            List<String> hits = e.getValue().stream().filter(k -> RequirementExtractor.matchesKeyword(lower, k)).toList();
            evidence.put(e.getKey(), new ArrayList<>(hits));
        }
        if ("research".equals(reqs.verb()) || "compare".equals(reqs.verb()) || "investigate".equals(reqs.verb())) {
            evidence.get("research").add(0, "verb: " + reqs.verb());
        }
        String domain = "general";
        int best = 0;
        for (Map.Entry<String, List<String>> e : evidence.entrySet()) {
            if (e.getValue().size() > best) {
                best = e.getValue().size();
                domain = e.getKey();
            }
        }
        String confidence = best >= 3 ? "high" : best == 2 ? "medium" : best == 1 ? "low" : "none";
        List<String> warnings = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        List<String> approvals = new ArrayList<>();
        List<String> deliverables = new ArrayList<>();
        DefaultWorkflows.Definition template;
        switch (domain) {
            case "software" -> {
                template = DefaultWorkflows.fullstack();
                deliverables.addAll(List.of("requirements.md and requirements.json", "architecture.md and api-contract.json",
                        "design-spec.md and tokens.json", "backend-implementation.md and schema.sql", "frontend-implementation.md",
                        "integration-report.md", "test-plan.md and qa-report.md", "security-review.md (when sign-in, payments or security are in scope)",
                        "docs.md", "final-report.md", "release-notes.md after approval"));
                approvals.add("handover: a person approves before the delivery package is published");
                limitations.add("Built-in agents produce specifications, contracts, a schema scaffold, plans and verification reports. "
                        + "They do not write or run application code. Assign implementation steps to an external agent (executor: external) "
                        + "for real code changes.");
                for (Map<String, Object> r : reqs.requirements().stream().map(RequirementExtractor.Requirement::toMap).toList()) {
                    if ("general".equals(r.get("category"))) {
                        warnings.add("No capability template recognises \"" + r.get("feature") + "\"; QA will report it as uncovered and the "
                                + "remediation step will add a generic capability. Edit the workflow if that is not what you want.");
                    }
                }
                if (reqs.categories().contains("payments")) {
                    approvals.add("payments-compliance: runs because payments are in scope");
                }
            }
            case "research" -> {
                template = DefaultWorkflows.research();
                deliverables.addAll(List.of("research-plan.md", "sources.md and sources.json", "findings.md", "report.md (cited)",
                        "fact-check.md", "editorial-summary.md"));
                limitations.add("There is no web access: only notes recorded in Memora (project or tenant memory) are used as sources. "
                        + "Record notes first; questions without evidence are disclosed as open questions, not answered.");
                limitations.add("The report lists cited evidence and themes. It does not write a free-form recommendation; that needs "
                        + "a person or a model-backed executor.");
            }
            case "data" -> {
                template = dataWorkflow();
                deliverables.add("analysis.md with a table of aggregated values");
                warnings.add("Provide the CSV text and name the column to group by and the numeric column to aggregate.");
                limitations.add("Only count, sum, average, minimum and maximum per group are computed.");
            }
            default -> {
                template = DefaultWorkflows.general();
                deliverables.addAll(List.of("requirements.md", "whatever the external agent reports back"));
                warnings.add("No built-in capability template matched this goal with enough evidence, so the work is delegated to an "
                        + "external agent. Edit the proposal to use other agents if you know which ones fit.");
                limitations.add("Memora itself only analyses the goal here; the external agent does the work.");
            }
        }
        Map<String, AgentSnapshot> installed = agents.allSnapshots(actor);
        List<Map<String, Object>> roles = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        Set<String> agentKeys = new LinkedHashSet<>();
        for (Object o : (Collection<?>) template.spec().get("steps")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) o;
            String agentKey = String.valueOf(s.get("agent"));
            agentKeys.add(agentKey);
            Optional<AgentDefinitionRequest> t = templates.find(agentKey);
            steps.add(Json.map("key", s.get("key"), "name", s.get("name"), "agent", agentKey, "dependsOn", s.get("dependsOn"),
                    "inputs", ((Map<?, ?>) s.get("inputs")).keySet(),
                    "expectedOutputs", t.map(d -> d.outputSchema().get("required")).orElse(null),
                    "condition", s.get("when"), "loop", s.get("loop"), "approval", s.get("approval"),
                    "remediation", s.get("remediation"),
                    "purpose", t.map(AgentDefinitionRequest::description).orElse(installed.containsKey(agentKey)
                            ? installed.get(agentKey).description() : null)));
        }
        for (String key : agentKeys) {
            AgentSnapshot a = installed.get(key);
            Optional<AgentDefinitionRequest> t = templates.find(key);
            roles.add(Json.map("agent", key, "name", a != null ? a.name() : t.map(AgentDefinitionRequest::name).orElse(key),
                    "role", a != null ? a.role() : t.map(AgentDefinitionRequest::role).orElse(null),
                    "installed", a != null, "template", t.isPresent(),
                    "executor", a != null ? a.executor() : t.map(AgentDefinitionRequest::executor).orElse(null),
                    "tools", a != null ? a.tools() : t.map(AgentDefinitionRequest::tools).orElse(List.of())));
            if (a == null && t.isEmpty()) {
                warnings.add("Agent " + key + " is neither installed nor a known template.");
            }
        }
        // Validate against installed agents plus the templates that accepting the proposal would install.
        Map<String, AgentSnapshot> withTemplates = new LinkedHashMap<>(installed);
        Map<String, String> statuses = new LinkedHashMap<>(agents.statusesByKey(actor));
        for (String key : agentKeys) {
            if (!withTemplates.containsKey(key)) {
                templates.find(key).ifPresent(d -> {
                    withTemplates.put(key, new AgentSnapshot(null, key, d.name(), d.role(), d.description(), d.instructions(),
                            d.capabilities(), d.tools(), d.inputSchema(), d.outputSchema(), d.memoryPolicy(), d.contextPolicy(),
                            d.executor(), d.behavior(), 60, 3, 1, 0, null, d.name()));
                    statuses.put(key, "ACTIVE");
                });
            }
        }
        WorkflowValidator.Result validation = validator.validate(template.spec(), withTemplates, statuses, false);
        validation.errors().forEach(p -> warnings.add("Validation: " + p.path() + " " + p.message()));
        List<Map<String, Object>> acceptance = reqs.requirements().stream()
                .map(r -> Json.map("requirement", r.id(), "text", r.text(), "criteria", r.acceptance())).toList();
        String subject = reqs.subject() == null ? "goal" : reqs.subject();
        String name = capitalize(domainLabel(domain)) + ": " + abbreviate(subject, 80);
        return new Proposal("rule-based-v1", goal, reqs.subject(),
                Json.map("key", domain, "confidence", confidence, "evidence", evidence.get(domain),
                        "template", template.key(), "alternatives", evidence),
                reqs.requirements().stream().map(RequirementExtractor.Requirement::toMap).toList(), deliverables, roles, steps,
                validation.analysis() == null ? List.of() : validation.analysis().parallelGroups(), acceptance, reqs.unclear(),
                reqs.missingInformation(), reqs.assumptions(), approvals, limitations, warnings, name,
                slug(template.key() + "-" + subject), template.spec());
    }

    private static DefaultWorkflows.Definition dataWorkflow() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("inputs", List.of(
                Json.map("name", "csv", "type", "text", "required", true, "description", "CSV data with a header row"),
                Json.map("name", "groupBy", "type", "string", "required", true, "description", "Column to group by"),
                Json.map("name", "metric", "type", "string", "required", true, "description", "Numeric column to aggregate")));
        spec.put("steps", List.of(Json.map("key", "analyze", "name", "Analyse the data", "agent", "data-analyst", "dependsOn", List.of(),
                "inputs", Json.map("csv", "${run.inputs.csv}", "groupBy", "${run.inputs.groupBy}", "metric", "${run.inputs.metric}"))));
        spec.put("completion", Json.map("requiredSteps", List.of("analyze")));
        return new DefaultWorkflows.Definition("csv-analysis", "CSV analysis", "Aggregate a CSV by a column", spec);
    }

    private static String domainLabel(String domain) {
        return switch (domain) {
            case "software" -> "software delivery";
            case "research" -> "research and report";
            case "data" -> "data analysis";
            default -> "analyse and delegate";
        };
    }

    private static String slug(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.length() > 60 ? slug.substring(0, 60).replaceAll("-+$", "") : slug;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
