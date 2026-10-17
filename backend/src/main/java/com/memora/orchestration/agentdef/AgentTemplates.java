package com.memora.orchestration.agentdef;

import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionRequest;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.TemplateView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Read-only system templates. Each is plain configuration for the generic executors: the role name has no
 * effect on execution, only the tools, policy and behavior do. Installing a template creates an editable,
 * tenant-owned agent definition.
 */
@Component
public class AgentTemplates {

    private record Entry(String category, AgentDefinitionRequest definition) {
    }

    private final Map<String, Entry> templates = new LinkedHashMap<>();

    public AgentTemplates() {
        // ----------------------------------------------------------- classic trio (Memora 1.0 roles)
        add("classic", def("planner", "Planner", "Planner",
                "Turns a request into numbered requirements with acceptance criteria and records them in shared memory.",
                "Extract requirements from the goal, write plan.md and remember each requirement for the team.",
                caps("memory.read", "memory.write", "analysis", "document", "artifact.write"),
                tools("context.build", "text.extract_requirements", "document.compose", "artifact.write", "memory.write"),
                schema("goal"), outputs("requirements"),
                behavior(l(
                        op("ctx", "context.build", m("query", "${input.goal}")),
                        op("reqs", "text.extract_requirements", m("text", "${input.goal}")),
                        op("doc", "document.compose", m("title", "Plan: ${ops.reqs.subject | the request}", "sections", l(
                                m("heading", "Goal", "body", "${input.goal}"),
                                m("heading", "Requirements", "items", "${ops.reqs.requirements}", "itemTemplate", "**{{id}}** {{text}}"),
                                m("heading", "Acceptance criteria", "items", "${ops.reqs.requirements}", "itemTemplate", "**{{id}}**: {{acceptance}}"),
                                m("heading", "Context from memory", "body", "${ops.ctx.rendered}")))),
                        op("save", "artifact.write", m("name", "plan.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        forEach("remember", "memory.write", "${ops.reqs.requirements}", m("content", "Requirement ${item.id}: ${item.text}",
                                "scope", "RUN", "category", "requirement", "tags", l("requirement"), "importance", 4))),
                        m("requirements", "${ops.reqs.requirements}", "count", "${ops.reqs.count}"),
                        failWhen("output.count", "eq", 0, "No requirements could be extracted"))));
        add("classic", def("developer", "Developer", "Developer",
                "Reads the requirements and shared context and writes implementation notes per requirement (deterministic; no code).",
                "Write one implementation note per requirement, citing its acceptance criteria.",
                caps("memory.read", "memory.write", "document", "artifact.write"),
                tools("context.build", "document.compose", "artifact.write", "memory.write"),
                schemaArray("requirements"), outputs("implemented"),
                behavior(l(
                        op("ctx", "context.build", m("query", "implementation decisions")),
                        op("doc", "document.compose", m("title", "Implementation notes", "intro",
                                "Deterministic notes generated from the requirements. No code was written or compiled.", "sections", l(
                                m("heading", "Notes", "items", "${input.requirements}", "itemTemplate",
                                        "**{{id}}** {{feature}}: deliver {{feature}} so that {{acceptance}}"),
                                m("heading", "Context used", "body", "${ops.ctx.rendered}")))),
                        op("save", "artifact.write", m("name", "implementation-notes.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("note", "memory.write", m("type", "EPISODIC", "scope", "RUN", "category", "event",
                                "content", "Developer wrote implementation notes for ${input.requirements.size} requirement(s)."))),
                        m("implemented", "${input.requirements.size}"), null)));
        add("classic", def("tester", "Tester", "Tester",
                "Verifies that every requirement id is covered by the implementation notes and writes a test report.",
                "Check coverage of every requirement in implementation-notes.md; fail if anything is missing.",
                caps("verify", "document", "artifact.write"),
                tools("checks.evaluate", "document.compose", "artifact.write"),
                schemaArray("requirements"), outputs("passed"),
                behavior(l(
                        op("checks", "checks.evaluate", m("checks", l(
                                m("name", "Implementation notes exist", "type", "artifactExists", "artifact", "implementation-notes.md"),
                                m("name", "Every requirement has a note", "type", "coverage", "ids", "${input.requirements}",
                                        "artifacts", l("implementation-notes.md"))))),
                        op("doc", "document.compose", m("title", "Test report", "sections", l(
                                m("heading", "Result", "body", "${ops.checks.summary}"),
                                m("heading", "Checks", "items", "${ops.checks.results}", "itemTemplate", "{{name}}: passed={{passed}} ({{detail}})")))),
                        op("save", "artifact.write", m("name", "test-report.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("passed", "${ops.checks.passed}", "summary", "${ops.checks.summary}"),
                        failWhen("output.passed", "eq", false, "Tests failed: ${output.summary}"))));

        // ----------------------------------------------------------- software delivery
        add("software", def("requirements-analyst", "Requirements Analyst", "Requirements analyst",
                "Extracts numbered requirements, acceptance criteria, unclear items, missing information and assumptions from a goal.",
                "Work only from the goal and shared memory. Flag what is unclear instead of guessing.",
                caps("memory.read", "memory.write", "analysis", "document", "artifact.write"),
                tools("context.build", "text.extract_requirements", "document.compose", "artifact.write", "memory.write"),
                schema("goal"), outputs("requirements", "count"),
                behavior(l(
                        op("ctx", "context.build", m("query", "${input.goal}")),
                        op("reqs", "text.extract_requirements", m("text", "${input.goal}")),
                        op("doc", "document.compose", m("title", "Requirements: ${ops.reqs.subject | the deliverable}",
                                "intro", "Extracted by pattern matching from the goal. Review and edit before relying on it.", "sections", l(
                                m("heading", "Goal", "body", "${input.goal}"),
                                m("heading", "Requirements", "items", "${ops.reqs.requirements}",
                                        "itemTemplate", "**{{id}}** [{{category}}, {{priority}}] {{text}}"),
                                m("heading", "Acceptance criteria", "items", "${ops.reqs.requirements}", "itemTemplate", "**{{id}}**: {{acceptance}}"),
                                m("heading", "Unclear items", "items", "${ops.reqs.unclear}", "emptyText", "Nothing unclear was detected."),
                                m("heading", "Missing information", "items", "${ops.reqs.missingInformation}", "emptyText", "None."),
                                m("heading", "Assumptions", "items", "${ops.reqs.assumptions}"),
                                m("heading", "Context from shared memory", "body", "${ops.ctx.rendered}")))),
                        op("save", "artifact.write", m("name", "requirements.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("json", "artifact.write", m("name", "requirements.json", "kind", "json", "content", "${ops.reqs.requirements}")),
                        forEach("remember", "memory.write", "${ops.reqs.requirements}", m("content", "Requirement ${item.id}: ${item.text}",
                                "scope", "RUN", "category", "requirement", "tags", l("requirement", "${item.category}"), "importance", 4))),
                        m("requirements", "${ops.reqs.requirements}", "count", "${ops.reqs.count}", "categories", "${ops.reqs.categories}",
                                "subject", "${ops.reqs.subject}", "unclear", "${ops.reqs.unclear}",
                                "missingInformation", "${ops.reqs.missingInformation}"),
                        failWhen("output.count", "eq", 0, "No requirements could be extracted from the goal; list the features explicitly"))));

        add("software", def("solution-architect", "Solution Architect", "Solution architect",
                "Maps requirements to API capabilities, a data model and cross-cutting approaches; records architecture decisions.",
                "Prefer decisions already recorded in shared memory (for example the project's database) over defaults.",
                caps("memory.read", "memory.write", "analysis", "document", "artifact.write"),
                tools("memory.search", "data.select", "catalog.match", "document.compose", "artifact.write", "memory.write"),
                schemaArray("requirements"), outputs("capabilities", "database"),
                behavior(l(
                        op("facts", "memory.search", m("query", "database", "types", l("SEMANTIC"), "limit", 20)),
                        op("db", "data.select", m("rows", "${ops.facts.items}", "where", m("path", "row.predicate", "op", "eq", "value", "uses.database"),
                                "pluck", "value")),
                        op("api", "catalog.match", m("items", "${input.requirements}", "catalog", Catalogs.API)),
                        op("cross", "catalog.match", m("items", "${input.requirements}", "catalog", Catalogs.CROSS_CUTTING)),
                        op("contract", "artifact.write", m("name", "api-contract.json", "kind", "json", "content", m(
                                "style", "REST over JSON", "database", "${ops.db.values.0 | postgresql}",
                                "capabilities", "${ops.api.entries}", "crossCutting", "${ops.cross.entries}"))),
                        op("doc", "document.compose", m("title", "Architecture and API contract", "sections", l(
                                m("heading", "Shape", "body", "A browser client calls a REST API over JSON; the API stores data in "
                                        + "${ops.db.values.0 | postgresql}. The database comes from shared memory when the project recorded one "
                                        + "(fact uses.database); otherwise PostgreSQL is assumed."),
                                m("heading", "API by capability", "items", "${ops.api.entries}", "itemTemplate", "**{{title}}** (covers {{covers}}): {{endpoints}}"),
                                m("heading", "Data model", "items", "${ops.api.entries}", "itemTemplate", "{{title}}: {{entities}}"),
                                m("heading", "Cross-cutting requirements", "items", "${ops.cross.entries}",
                                        "itemTemplate", "**{{title}}** (covers {{covers}}): {{approach}}", "emptyText", "None."),
                                m("heading", "Requirements with no API capability", "items", "${ops.api.unmatched}", "itemTemplate", "{{text}}",
                                        "emptyText", "Every requirement maps to an API capability.")))),
                        op("save", "artifact.write", m("name", "architecture.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("d1", "memory.write", m("content", "Decision: the API is REST over JSON.", "scope", "PROJECT", "category", "decision",
                                "subject", "project", "predicate", "architecture.api_style", "value", "rest-json", "importance", 4,
                                "tags", l("decision", "architecture"))),
                        op("d2", "memory.write", m("content", "Decision: application data is stored in ${ops.db.values.0 | postgresql}.",
                                "scope", "PROJECT", "category", "decision", "subject", "project", "predicate", "architecture.database",
                                "value", "${ops.db.values.0 | postgresql}", "importance", 4, "tags", l("decision", "database")))),
                        m("database", "${ops.db.values.0 | postgresql}", "capabilities", "${ops.api.entries}",
                                "crossCutting", "${ops.cross.entries}", "unmapped", "${ops.api.unmatched}", "coveredIds", "${ops.api.coveredIds}"),
                        null)));

        add("software", def("ux-designer", "UX Designer", "UX/UI designer",
                "Produces a design specification: screens per requirement, a component inventory and design tokens.",
                "Every screen must be usable at phone width and by keyboard.",
                caps("analysis", "document", "artifact.write", "memory.write"),
                tools("catalog.match", "document.compose", "artifact.write", "memory.write"),
                schemaArray("requirements"), outputs("screens"),
                behavior(l(
                        op("screens", "catalog.match", m("items", "${input.requirements}", "catalog", Catalogs.SCREENS)),
                        op("doc", "document.compose", m("title", "Design specification", "sections", l(
                                m("heading", "Screens", "items", "${ops.screens.entries}",
                                        "itemTemplate", "**{{title}}** (covers {{covers}}): screens {{screens}}; components {{components}}"),
                                m("heading", "Design tokens", "body", "Colour primary #4F46E5, surface #FFFFFF, text #0F172A, danger #E11D48. "
                                        + "Spacing on an 8 px grid. Type scale 12/14/16/20/24 px. Radius 8 px. Breakpoints 640 px and 1024 px."),
                                m("heading", "Accessibility", "body", "Contrast at least 4.5:1, visible focus rings, every input labelled, "
                                        + "every action reachable by keyboard."),
                                m("heading", "Requirements with no screen", "items", "${ops.screens.unmatched}", "itemTemplate", "{{text}}",
                                        "emptyText", "Every requirement has a screen.")))),
                        op("save", "artifact.write", m("name", "design-spec.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("tokens", "artifact.write", m("name", "tokens.json", "kind", "json", "content", m("color", m("primary", "#4F46E5",
                                "surface", "#FFFFFF", "text", "#0F172A", "danger", "#E11D48"), "spacingPx", 8, "radiusPx", 8,
                                "typeScalePx", l(12, 14, 16, 20, 24), "breakpointsPx", l(640, 1024)))),
                        op("decision", "memory.write", m("content", "Decision: the UI uses the design tokens in tokens.json (primary #4F46E5, 8 px grid).",
                                "scope", "PROJECT", "category", "decision", "subject", "project", "predicate", "design.tokens",
                                "value", "tokens.json v1", "tags", l("decision", "design")))),
                        m("screens", "${ops.screens.entries}", "unmatched", "${ops.screens.unmatched}", "coveredIds", "${ops.screens.coveredIds}"),
                        null)));

        add("software", def("backend-engineer", "Backend Engineer", "Backend engineer",
                "Writes a backend implementation plan and a SQL schema scaffold from the API contract (not compiled or run).",
                "Specify validation and error handling for every endpoint.",
                caps("document", "artifact.write"),
                tools("document.compose", "artifact.write"),
                schemaArray("capabilities"), outputs("artifacts"),
                behavior(l(
                        op("doc", "document.compose", m("title", "Backend implementation plan (scaffold)",
                                "intro", "Generated deterministically from the API contract. It specifies handlers; it has not been compiled or run.",
                                "sections", l(
                                        m("heading", "Endpoints", "items", "${input.capabilities}", "itemTemplate",
                                                "**{{title}}**: {{endpoints}}. Malformed input returns 400, protected routes return 401 "
                                                        + "without a session, unknown ids return 404."),
                                        m("heading", "Database", "body", "Database: ${input.database | postgresql}. Tables are created by schema.sql "
                                                + "as the first versioned migration."),
                                        m("heading", "Errors", "body", "Errors are returned as RFC 7807 problem details: 401 unauthenticated, "
                                                + "403 forbidden, 404 not found, 409 conflict, 422 invalid.")))),
                        op("sql", "document.compose", m("title", "Schema scaffold generated from the API contract (${input.database | postgresql})",
                                "style", "plain", "commentPrefix", "--", "sections", l(
                                        m("heading", "Tables", "items", "${input.capabilities}", "itemTemplate", "{{ddl}}")))),
                        op("save", "artifact.write", m("name", "backend-implementation.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("schema", "artifact.write", m("name", "schema.sql", "kind", "code", "content", "${ops.sql.text}"))),
                        m("artifacts", l("backend-implementation.md", "schema.sql"), "endpointGroups", "${input.capabilities.size}"), null)));

        add("software", def("frontend-engineer", "Frontend Engineer", "Frontend engineer",
                "Maps each screen from the design to the API capabilities it calls and writes a frontend implementation plan.",
                "Use the approved design tokens and only call endpoints in the API contract.",
                caps("analysis", "document", "artifact.write"),
                tools("data.select", "data.join", "document.compose", "artifact.write"),
                schemaArray("screens", "capabilities"), outputs("unmatchedCount"),
                behavior(l(
                        op("ui", "data.select", m("rows", "${input.screens}", "where", m("path", "row.apiRequired", "op", "ne", "value", false))),
                        op("join", "data.join", m("left", "${ops.ui.rows}", "right", "${input.capabilities}", "on", "key", "rightPrefix", "api_")),
                        op("doc", "document.compose", m("title", "Frontend implementation plan (scaffold)",
                                "intro", "Screens from design-spec.md and the endpoints they call from api-contract.json. Not compiled or run.",
                                "sections", l(
                                        m("heading", "Screens and endpoints", "items", "${ops.join.rows}",
                                                "itemTemplate", "**{{title}}** ({{screens}}): calls {{api_endpoints}}"),
                                        m("heading", "Shared layout", "body", "AppShell, NavBar and Footer wrap every screen; styles come from tokens.json."),
                                        m("heading", "Screens without a backend capability", "items", "${ops.join.unmatchedLeft}",
                                                "itemTemplate", "{{title}}", "emptyText", "Every data screen has a backend capability.")))),
                        op("save", "artifact.write", m("name", "frontend-implementation.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("screens", "${ops.join.count}", "unmatched", "${ops.join.unmatchedLeft}", "unmatchedCount", "${ops.join.unmatchedCount}"),
                        null)));

        add("software", def("integration-engineer", "Integration Engineer", "Integration engineer",
                "Checks that the frontend and backend plans agree: every artifact exists and every data screen has an API capability.",
                "Verify the artifacts themselves; do not trust step status labels.",
                caps("verify", "document", "artifact.write"),
                tools("checks.evaluate", "document.compose", "artifact.write"),
                Map.of("type", "object", "required", l("unmatchedScreens"), "properties", m("unmatchedScreens", m("type", "integer"))),
                outputs("passed"),
                behavior(l(
                        op("checks", "checks.evaluate", m("checks", l(
                                m("name", "API contract exists", "type", "artifactExists", "artifact", "api-contract.json"),
                                m("name", "Design specification exists", "type", "artifactExists", "artifact", "design-spec.md"),
                                m("name", "Backend plan exists", "type", "artifactExists", "artifact", "backend-implementation.md"),
                                m("name", "Schema scaffold exists", "type", "artifactExists", "artifact", "schema.sql"),
                                m("name", "Frontend plan exists", "type", "artifactExists", "artifact", "frontend-implementation.md"),
                                m("name", "Every data screen has an API capability", "type", "condition",
                                        "condition", m("path", "value", "op", "eq", "value", 0), "value", "${input.unmatchedScreens}"),
                                m("name", "Frontend calls documented endpoints", "type", "artifactContains", "artifact",
                                        "frontend-implementation.md", "text", "/api/")))),
                        op("doc", "document.compose", m("title", "Integration report", "sections", l(
                                m("heading", "Result", "body", "${ops.checks.summary}"),
                                m("heading", "Checks", "items", "${ops.checks.results}", "itemTemplate", "{{name}}: passed={{passed}} ({{detail}})")))),
                        op("save", "artifact.write", m("name", "integration-report.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("passed", "${ops.checks.passed}", "summary", "${ops.checks.summary}", "results", "${ops.checks.results}"),
                        failWhen("output.passed", "eq", false, "Integration checks failed: ${output.summary}"))));

        add("software", def("qa-engineer", "QA Engineer", "QA engineer",
                "Writes a test plan from the acceptance criteria and verifies that every requirement is covered by the contract, "
                        + "the design or a gap resolution. Reports missing coverage so a remediation step can fix it.",
                "A requirement counts as covered only if its id appears in a design or contract artifact.",
                caps("verify", "document", "artifact.write", "memory.write"),
                tools("checks.evaluate", "document.compose", "artifact.write", "memory.write"),
                schemaArray("requirements"), outputs("passed", "missing"),
                behavior(l(
                        op("plan", "document.compose", m("title", "Test plan", "sections", l(
                                m("heading", "Test cases", "items", "${input.requirements}",
                                        "itemTemplate", "**{{id}}** {{feature}}: verify that {{acceptance}}")))),
                        op("savePlan", "artifact.write", m("name", "test-plan.md", "kind", "markdown", "content", "${ops.plan.markdown}")),
                        op("checks", "checks.evaluate", m("checks", l(
                                m("name", "Every requirement is covered by the API contract, the design or a gap resolution", "type", "coverage",
                                        "ids", "${input.requirements}", "artifacts", l("api-contract.json", "design-spec.md", "gap-resolution.md")),
                                m("name", "Integration report exists", "type", "artifactExists", "artifact", "integration-report.md"),
                                m("name", "Test plan exists", "type", "artifactExists", "artifact", "test-plan.md")))),
                        op("report", "document.compose", m("title", "QA report (iteration ${step.iteration})", "sections", l(
                                m("heading", "Result", "body", "${ops.checks.summary}"),
                                m("heading", "Checks", "items", "${ops.checks.results}", "itemTemplate", "{{name}}: passed={{passed}} ({{detail}})"),
                                m("heading", "Requirements without coverage", "items", "${ops.checks.missing}", "emptyText", "None.")))),
                        op("saveReport", "artifact.write", m("name", "qa-report.md", "kind", "markdown", "content", "${ops.report.markdown}")),
                        whenOp("gap", "memory.write", m("path", "ops.checks.passed", "op", "eq", "value", false), m("type", "EPISODIC",
                                "scope", "RUN", "category", "event", "importance", 4, "tags", l("qa", "gap"),
                                "content", "QA iteration ${step.iteration} found requirements without coverage: ${ops.checks.missing}"))),
                        m("passed", "${ops.checks.passed}", "missing", "${ops.checks.missing}", "summary", "${ops.checks.summary}",
                                "iteration", "${step.iteration}"), null)));

        add("software", def("remediation-engineer", "Remediation Engineer", "Remediation engineer",
                "Closes coverage gaps reported by QA by adding a generic management capability (admin API and screen) per "
                        + "uncovered requirement, and records the decision.",
                "Only address the requirement ids QA reported as missing.",
                caps("analysis", "document", "artifact.write", "memory.write"),
                tools("data.select", "document.compose", "artifact.write", "memory.write"),
                schemaArray("missing", "requirements"), outputs("resolved"),
                behavior(l(
                        op("sel", "data.select", m("rows", "${input.requirements}", "where", m("path", "row.id", "op", "in", "value", "${input.missing}"))),
                        op("doc", "document.compose", m("title", "Gap resolution (after QA iteration ${step.iteration})",
                                "intro", "Requirements QA found without an API capability or screen. Each gets a generic management "
                                        + "capability so it can be designed and tested; review whether that fits.", "sections", l(
                                        m("heading", "Added capabilities", "items", "${ops.sel.rows}", "itemTemplate",
                                                "**{{id}}** {{feature}}: admin API (list, create, update, delete) under /api/admin/ and a "
                                                        + "management screen with a filterable table and an edit form.")))),
                        op("save", "artifact.write", m("name", "gap-resolution.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("decision", "memory.write", m("content", "Decision: requirement(s) ${input.missing} are covered by generic admin "
                                + "management capabilities added during remediation.", "scope", "PROJECT", "category", "decision",
                                "tags", l("decision", "remediation")))),
                        m("resolved", "${input.missing}", "count", "${ops.sel.count}"),
                        failWhen("output.count", "eq", 0, "QA reported no uncovered requirement ids, so there is nothing this agent can remediate"))));

        add("software", def("security-reviewer", "Security Reviewer", "Security reviewer",
                "Checks the contract, schema and backend plan against a security checklist (sign-in, hashed credentials, no card "
                        + "data, 401 on protected routes) and records the checklist as a reusable procedure.",
                "Report findings; never weaken a check to make it pass.",
                caps("verify", "document", "artifact.write", "memory.write"),
                tools("checks.evaluate", "document.compose", "artifact.write", "memory.write"),
                Map.of("type", "object"), outputs("passed"),
                behavior(l(
                        op("checks", "checks.evaluate", m("checks", l(
                                m("name", "Sign-in and sign-out endpoints are specified", "type", "artifactContains", "artifact", "api-contract.json",
                                        "allOf", l("/api/auth/login", "/api/auth/logout")),
                                m("name", "Credentials are stored as hashes", "type", "artifactContains", "artifact", "schema.sql", "text", "password_hash"),
                                m("name", "No card numbers are stored", "type", "artifactLacks", "artifact", "schema.sql", "anyOf", l("card_number", "cvv")),
                                m("name", "Unauthenticated requests are rejected", "type", "artifactContains", "artifact",
                                        "backend-implementation.md", "text", "401"),
                                m("name", "Errors use problem details", "type", "artifactContains", "artifact", "backend-implementation.md",
                                        "text", "problem details", "severity", "warning")))),
                        op("doc", "document.compose", m("title", "Security review", "sections", l(
                                m("heading", "Result", "body", "${ops.checks.summary}"),
                                m("heading", "Findings", "items", "${ops.checks.results}", "itemTemplate", "{{name}}: passed={{passed}} ({{detail}})")))),
                        op("save", "artifact.write", m("name", "security-review.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("lesson", "memory.write", m("type", "PROCEDURAL", "scope", "WORKFLOW", "category", "procedure", "importance", 4,
                                "content", "Security checklist for web applications: specify sign-in and sign-out endpoints, store only "
                                        + "credential hashes, never store card data, reject unauthenticated requests with 401.",
                                "tags", l("security", "checklist")))),
                        m("passed", "${ops.checks.passed}", "summary", "${ops.checks.summary}", "findings", "${ops.checks.results}"),
                        failWhen("output.passed", "eq", false, "Security review failed: ${output.summary}"))));

        add("software", def("documentation-writer", "Documentation Writer", "Documentation writer",
                "Writes setup, API and usage documentation from the run's actual artifacts.",
                "Document only what the artifacts contain.",
                caps("artifact.read", "document", "artifact.write"),
                tools("artifact.list", "document.compose", "artifact.write"),
                Map.of("type", "object"), outputs("artifact"),
                behavior(l(
                        op("list", "artifact.list", m()),
                        op("doc", "document.compose", m("title", "Documentation", "sections", l(
                                m("heading", "Overview", "body", "${run.goal}"),
                                m("heading", "Setup", "body", "Apply schema.sql to an empty ${input.database | postgresql} database, configure the "
                                        + "API with its connection settings, then serve the frontend. Each step is described in the implementation plans."),
                                m("heading", "API reference", "items", "${input.capabilities}", "itemTemplate", "**{{title}}**: {{endpoints}}"),
                                m("heading", "Screens", "items", "${input.screens}", "itemTemplate", "**{{title}}**: {{screens}}"),
                                m("heading", "Requirements", "items", "${input.requirements}", "itemTemplate", "{{id}}: {{text}}"),
                                m("heading", "Artifacts in this delivery", "items", "${ops.list.names}")))),
                        op("save", "artifact.write", m("name", "docs.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("artifact", "docs.md", "artifactCount", "${ops.list.count}"), null)));

        add("software", def("final-reviewer", "Final Reviewer", "Final reviewer",
                "Checks the delivery against the original acceptance criteria by inspecting the actual artifacts, not step "
                        + "statuses, and writes an honest final report.",
                "Pass only when every deliverable exists and every requirement is covered.",
                caps("verify", "document", "artifact.write", "memory.write"),
                tools("checks.evaluate", "document.compose", "artifact.write", "memory.write"),
                schemaArray("requirements"), outputs("passed"),
                behavior(l(
                        op("checks", "checks.evaluate", m("checks", l(
                                exists("requirements.md"), exists("architecture.md"), exists("api-contract.json"), exists("design-spec.md"),
                                exists("backend-implementation.md"), exists("schema.sql"), exists("frontend-implementation.md"),
                                exists("integration-report.md"), exists("test-plan.md"), exists("qa-report.md"), exists("docs.md"),
                                m("name", "Every requirement is covered", "type", "coverage", "ids", "${input.requirements}",
                                        "artifacts", l("api-contract.json", "design-spec.md", "gap-resolution.md")),
                                m("name", "QA passed", "type", "condition", "condition", m("path", "value", "op", "eq", "value", true),
                                        "value", "${input.qaPassed}"),
                                m("name", "Security review passed or was not needed", "type", "condition",
                                        "condition", m("path", "value", "op", "in", "value", l("SUCCEEDED", "SKIPPED")),
                                        "value", "${input.securityStatus}")))),
                        op("doc", "document.compose", m("title", "Final review", "sections", l(
                                m("heading", "Verdict", "body", "${ops.checks.summary}"),
                                m("heading", "Checks against the actual artifacts", "items", "${ops.checks.results}",
                                        "itemTemplate", "{{name}}: passed={{passed}} ({{detail}})"),
                                m("heading", "What this run produced", "body", "Requirements, an architecture and API contract, a design "
                                        + "specification with tokens, backend and frontend implementation plans, a SQL schema scaffold, "
                                        + "integration, QA and security reports, a test plan and documentation. They were generated "
                                        + "deterministically from the goal and shared memory. No application code was compiled, deployed "
                                        + "or run; to build it, assign implementation steps to an external agent or a person.")))),
                        op("save", "artifact.write", m("name", "final-report.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("record", "memory.write", m("type", "EPISODIC", "scope", "PROJECT", "category", "review", "importance", 4,
                                "content", "Final review of run ${run.name}: ${ops.checks.summary}"))),
                        m("passed", "${ops.checks.passed}", "summary", "${ops.checks.summary}", "results", "${ops.checks.results}"),
                        failWhen("output.passed", "eq", false, "Final review failed: ${output.summary}"))));

        add("software", def("release-manager", "Release Manager", "Release manager",
                "Packages the delivery after a person approves it: writes release notes listing every artifact and the verdict.",
                "Run only after explicit human approval.",
                caps("artifact.read", "document", "artifact.write"),
                tools("artifact.list", "document.compose", "artifact.write"),
                Map.of("type", "object"), outputs("artifact"),
                behavior(l(
                        op("list", "artifact.list", m()),
                        op("doc", "document.compose", m("title", "Release notes", "sections", l(
                                m("heading", "Verdict", "body", "${input.verdict | not recorded}"),
                                m("heading", "Approval", "body", "A person approved this handover in Memora (see the run's approval record)."),
                                m("heading", "Artifacts", "items", "${ops.list.artifacts}", "itemTemplate", "{{name}} v{{version}} (sha256 {{checksum}})")))),
                        op("save", "artifact.write", m("name", "release-notes.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("artifact", "release-notes.md", "artifactCount", "${ops.list.count}"), null)));

        // ----------------------------------------------------------- research and report
        add("research", def("research-planner", "Research Planner", "Research planner",
                "Splits a research brief into numbered questions and states the method.",
                "Questions must be answerable from recorded notes.",
                caps("analysis", "document", "artifact.write"),
                tools("text.extract_requirements", "document.compose", "artifact.write"),
                schema("topic"), outputs("questions", "count"),
                behavior(l(
                        op("q", "text.extract_requirements", m("text", "${input.topic}")),
                        op("doc", "document.compose", m("title", "Research plan", "sections", l(
                                m("heading", "Brief", "body", "${input.topic}"),
                                m("heading", "Questions", "items", "${ops.q.requirements}", "itemTemplate", "**{{id}}** {{feature}}"),
                                m("heading", "Method", "body", "Evidence is retrieved from notes recorded in Memora (project and tenant "
                                        + "memory). There is no web access: anything not recorded cannot be used.")))),
                        op("save", "artifact.write", m("name", "research-plan.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("questions", "${ops.q.requirements}", "count", "${ops.q.count}"),
                        failWhen("output.count", "eq", 0, "No research questions could be identified in the brief"))));

        add("research", def("source-collector", "Source Collector", "Source collector",
                "Retrieves recorded notes relevant to the brief from project and tenant memory and lists them as citable sources.",
                "Use only recorded notes; report when there are none.",
                caps("memory.read", "document", "artifact.write"),
                tools("memory.search", "document.compose", "artifact.write"),
                schema("topic"), outputs("sources", "count"),
                behavior(l(
                        op("notes", "memory.search", m("query", "${input.topic}", "types", l("SEMANTIC", "EPISODIC"),
                                "scopes", l("PROJECT", "TENANT"), "limit", 20)),
                        op("doc", "document.compose", m("title", "Sources", "sections", l(
                                m("heading", "Recorded notes", "items", "${ops.notes.citations}", "emptyText", "No recorded notes match.")))),
                        op("save", "artifact.write", m("name", "sources.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("json", "artifact.write", m("name", "sources.json", "kind", "json", "content", "${ops.notes.items}"))),
                        m("sources", "${ops.notes.items}", "count", "${ops.notes.count}"),
                        failWhen("output.count", "eq", 0, "No recorded notes match the brief. Add notes in Conversations or the "
                                + "Memory explorer, then retry this step."))));

        add("research", def("research-analyst", "Research Analyst", "Research analyst",
                "Finds the evidence for each question and the recurring themes across the sources.",
                "Every finding must point at the notes it rests on.",
                caps("memory.read", "analysis", "document", "artifact.write"),
                tools("memory.search", "text.keywords", "data.select", "document.compose", "artifact.write"),
                schemaArray("questions", "sources"), outputs("evidence"),
                behavior(l(
                        op("themes", "text.keywords", m("texts", "${input.sources}", "top", 8)),
                        forEach("evidence", "memory.search", "${input.questions}", m("query", "${item.feature}",
                                "types", l("SEMANTIC", "EPISODIC"), "scopes", l("PROJECT", "TENANT"), "limit", 5)),
                        op("supported", "data.select", m("rows", "${ops.evidence}", "where", m("path", "row.count", "op", "gt", "value", 0))),
                        op("doc", "document.compose", m("title", "Findings", "sections", l(
                                m("heading", "Evidence by question", "items", "${ops.evidence}",
                                        "itemTemplate", "**{{query}}** ({{count}} note(s)): {{citations}}"),
                                m("heading", "Recurring themes", "items", "${ops.themes.keywords}", "itemTemplate", "{{term}} ({{count}})")))),
                        op("save", "artifact.write", m("name", "findings.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("evidence", "${ops.evidence}", "themes", "${ops.themes.terms}", "supportedCount", "${ops.supported.count}"),
                        null)));

        add("research", def("report-writer", "Report Writer", "Report writer",
                "Writes the report from supported findings only, with citations, and states plainly what it cannot do.",
                "Never state a finding without a citation.",
                caps("analysis", "document", "artifact.write"),
                tools("data.select", "document.compose", "artifact.write"),
                schemaArray("evidence"), outputs("artifact"),
                behavior(l(
                        op("sel", "data.select", m("rows", "${input.evidence}", "where", m("path", "row.count", "op", "gt", "value", 0))),
                        op("doc", "document.compose", m("title", "Research report", "sections", l(
                                m("heading", "Brief", "body", "${run.goal}"),
                                m("heading", "Method", "body", "Deterministic retrieval over notes recorded in Memora. Each finding lists "
                                        + "the notes it rests on as [m:id] citations."),
                                m("heading", "Findings", "items", "${ops.sel.rows}", "itemTemplate", "**{{query}}**: {{citations}}",
                                        "emptyText", "No question is supported by a recorded note."),
                                m("heading", "Themes", "body", "${input.themes}"),
                                m("heading", "Recommendation", "body", "Not generated: this build has no language model. Draw the "
                                        + "recommendation from the cited findings.")))),
                        op("save", "artifact.write", m("name", "report.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("artifact", "report.md", "findings", "${ops.sel.count}"), null)));

        add("research", def("fact-checker", "Fact Checker", "Fact checker",
                "Checks that every question either has cited evidence in the report or is disclosed as an open question.",
                "Fail the check rather than accept an unsupported claim.",
                caps("analysis", "verify", "document", "artifact.write"),
                tools("data.select", "checks.evaluate", "document.compose", "artifact.write"),
                schemaArray("evidence"), outputs("passed"),
                behavior(l(
                        op("unsupported", "data.select", m("rows", "${input.evidence}", "where", m("path", "row.count", "op", "eq", "value", 0),
                                "format", "Open question: {{query}}")),
                        op("checks", "checks.evaluate", m("checks", l(
                                m("name", "Report exists", "type", "artifactExists", "artifact", "report.md"),
                                m("name", "At least one question is supported by a recorded note", "type", "condition",
                                        "condition", m("path", "value", "op", "gt", "value", 0), "value", "${input.supportedCount}"),
                                m("name", "Questions without evidence are disclosed as open questions", "type", "artifactContains",
                                        "artifact", "report.md", "allOf", "${ops.unsupported.values}")))),
                        op("doc", "document.compose", m("title", "Fact check (iteration ${step.iteration})", "sections", l(
                                m("heading", "Result", "body", "${ops.checks.summary}"),
                                m("heading", "Checks", "items", "${ops.checks.results}", "itemTemplate", "{{name}}: passed={{passed}} ({{detail}})")))),
                        op("save", "artifact.write", m("name", "fact-check.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("passed", "${ops.checks.passed}", "summary", "${ops.checks.summary}", "unsupported", "${ops.unsupported.count}"),
                        null)));

        add("research", def("report-reviser", "Report Reviser", "Report reviser",
                "Revises the report after a failed fact check by disclosing unsupported questions as open questions.",
                "Disclose; do not invent evidence.",
                caps("artifact.read", "analysis", "document", "artifact.write"),
                tools("artifact.read", "data.select", "document.compose", "artifact.write"),
                schemaArray("evidence"), outputs("disclosed"),
                behavior(l(
                        op("prev", "artifact.read", m("name", "report.md")),
                        op("uns", "data.select", m("rows", "${input.evidence}", "where", m("path", "row.count", "op", "eq", "value", 0))),
                        op("sec", "document.compose", m("title", "Open questions", "intro",
                                "No recorded note supports these questions, so they are disclosed instead of answered.", "sections", l(
                                m("heading", "Questions without evidence", "items", "${ops.uns.rows}", "itemTemplate", "Open question: {{query}}")))),
                        op("save", "artifact.write", m("name", "report.md", "kind", "markdown",
                                "content", "${ops.prev.content}\n\n${ops.sec.markdown}"))),
                        m("disclosed", "${ops.uns.count}"), null)));

        add("research", def("editor", "Editor", "Editor",
                "Checks the finished report's structure and the fact check, then writes the editorial summary.",
                "Approve only a report that passed its fact check.",
                caps("verify", "document", "artifact.write", "memory.write"),
                tools("checks.evaluate", "document.compose", "artifact.write", "memory.write"),
                Map.of("type", "object"), outputs("passed"),
                behavior(l(
                        op("checks", "checks.evaluate", m("checks", l(
                                exists("research-plan.md"), exists("sources.md"), exists("findings.md"), exists("report.md"),
                                m("name", "Report has method and findings sections", "type", "artifactContains", "artifact", "report.md",
                                        "allOf", l("## Method", "## Findings")),
                                m("name", "Fact check passed", "type", "condition", "condition", m("path", "value", "op", "eq", "value", true),
                                        "value", "${input.factCheckPassed}")))),
                        op("doc", "document.compose", m("title", "Editorial summary", "sections", l(
                                m("heading", "Verdict", "body", "${ops.checks.summary}"),
                                m("heading", "Checks", "items", "${ops.checks.results}", "itemTemplate", "{{name}}: passed={{passed}} ({{detail}})")))),
                        op("save", "artifact.write", m("name", "editorial-summary.md", "kind", "markdown", "content", "${ops.doc.markdown}")),
                        op("record", "memory.write", m("type", "EPISODIC", "scope", "PROJECT", "category", "review",
                                "content", "Research report reviewed: ${ops.checks.summary}"))),
                        m("passed", "${ops.checks.passed}", "summary", "${ops.checks.summary}"),
                        failWhen("output.passed", "eq", false, "Editorial review failed: ${output.summary}"))));

        // ----------------------------------------------------------- general purpose
        add("general", new AgentDefinitionRequest("external-developer", "External Developer", "An agent that works outside Memora "
                + "(a person, a script or an AI coding agent with its own API key). Use it for real code changes.",
                "Software developer (external)", "Implement the step in the real repository, then complete the handoff with "
                + "{\"output\": {\"summary\": \"...\", \"changedFiles\": [...]}}.",
                List.of("code.write"), List.of(), Map.of(), Map.of("type", "object", "required", l("summary"),
                "properties", m("summary", m("type", "string"))), m(), m(), "external",
                m("brief", "Do the work described in the handoff and report a summary of what changed."), 3600, 2, 5, null));

        add("data", def("data-analyst", "Data Analyst", "Data analyst",
                "Parses CSV data, aggregates a metric by a column and writes an analysis with a table.",
                "Report only figures computed from the data.",
                caps("analysis", "document", "artifact.write"),
                tools("data.parse_csv", "data.aggregate", "document.compose", "artifact.write"),
                Map.of("type", "object", "required", l("csv", "groupBy", "metric"), "properties", m("csv", m("type", "string"),
                        "groupBy", m("type", "string"), "metric", m("type", "string"))), outputs("groups"),
                behavior(l(
                        op("data", "data.parse_csv", m("content", "${input.csv}")),
                        op("agg", "data.aggregate", m("rows", "${ops.data.rows}", "groupBy", "${input.groupBy}", "metrics",
                                l(m("op", "count"), m("field", "${input.metric}", "op", "sum"), m("field", "${input.metric}", "op", "avg")))),
                        op("doc", "document.compose", m("title", "Analysis of ${input.metric} by ${input.groupBy}", "sections", l(
                                m("heading", "Data", "body", "${ops.data.rowCount} row(s), columns: ${ops.data.columns}"),
                                m("heading", "Results", "items", "${ops.agg.groups}", "itemTemplate", "**{{group}}**: {{count}} row(s); {{metrics}}")))),
                        op("save", "artifact.write", m("name", "analysis.md", "kind", "markdown", "content", "${ops.doc.markdown}"))),
                        m("groups", "${ops.agg.groups}", "rowCount", "${ops.data.rowCount}"),
                        failWhen("output.rowCount", "eq", 0, "The CSV has no data rows"))));
    }

    public Optional<AgentDefinitionRequest> find(String key) {
        return Optional.ofNullable(templates.get(key)).map(Entry::definition);
    }

    public List<String> keys() {
        return new ArrayList<>(templates.keySet());
    }

    public List<TemplateView> views(Predicate<String> installed) {
        return templates.entrySet().stream().map(e -> {
            AgentDefinitionRequest d = e.getValue().definition();
            return new TemplateView(e.getKey(), d.name(), d.role(), d.description(), e.getValue().category(), d.capabilities(),
                    d.tools(), d.executor(), installed.test(e.getKey()), d);
        }).toList();
    }

    private void add(String category, AgentDefinitionRequest definition) {
        templates.put(definition.key(), new Entry(category, definition));
    }

    // ------------------------------------------------------------------ builders

    private static AgentDefinitionRequest def(String key, String name, String role, String description, String instructions,
                                              List<String> capabilities, List<String> tools, Map<String, Object> inputSchema,
                                              Map<String, Object> outputSchema, Map<String, Object> behavior) {
        return new AgentDefinitionRequest(key, name, description, role, instructions, capabilities, tools, inputSchema, outputSchema,
                m("read", l("RUN", "PROJECT", "WORKFLOW", "TENANT"), "write", l("RUN", "PROJECT", "WORKFLOW"), "maxItems", 12,
                        "maxChars", 4000),
                m("includeArtifacts", true), "template", behavior, 60, 3, 1, null);
    }

    private static List<String> caps(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> tools(String... values) {
        return Arrays.asList(values);
    }

    private static Map<String, Object> schema(String requiredString) {
        return m("type", "object", "required", l(requiredString), "properties", m(requiredString, m("type", "string", "minLength", 3)));
    }

    private static Map<String, Object> schemaArray(String... requiredArrays) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (String r : requiredArrays) {
            props.put(r, m("type", "array"));
        }
        return m("type", "object", "required", Arrays.asList(requiredArrays), "properties", props);
    }

    private static Map<String, Object> outputs(String... required) {
        return m("type", "object", "required", Arrays.asList(required));
    }

    private static Map<String, Object> behavior(List<Object> operations, Map<String, Object> output, Map<String, Object> failWhen) {
        Map<String, Object> b = m("operations", operations, "output", output);
        if (failWhen != null) {
            b.put("failWhen", failWhen);
        }
        return b;
    }

    private static Map<String, Object> failWhen(String path, String op, Object value, String reason) {
        return m("condition", m("path", path, "op", op, "value", value), "reason", reason, "retryable", false);
    }

    private static Map<String, Object> op(String id, String tool, Map<String, Object> args) {
        return m("id", id, "tool", tool, "args", args);
    }

    private static Map<String, Object> forEach(String id, String tool, String items, Map<String, Object> args) {
        return m("id", id, "tool", tool, "forEach", items, "args", args);
    }

    private static Map<String, Object> whenOp(String id, String tool, Map<String, Object> when, Map<String, Object> args) {
        return m("id", id, "tool", tool, "when", when, "args", args);
    }

    private static Map<String, Object> exists(String artifact) {
        return m("name", artifact + " exists", "type", "artifactExists", "artifact", artifact);
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }

    static List<Object> l(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
