package com.memora.orchestration.install;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Example workflows installed for every tenant. They are ordinary, editable workflow definitions built from
 * the agent templates; the engine has no knowledge of them.
 */
public final class DefaultWorkflows {

    public static final String FULLSTACK = "fullstack-delivery";
    public static final String RESEARCH = "research-report";
    public static final String CLASSIC = "plan-build-test";
    public static final String GENERAL = "general-delegation";

    private DefaultWorkflows() {
    }

    public record Definition(String key, String name, String description, Map<String, Object> spec) {
    }

    public static List<Definition> all() {
        return List.of(fullstack(), research(), classic());
    }

    public static Definition fullstack() {
        return new Definition(FULLSTACK, "Full-stack web delivery",
                "Requirements → architecture and design in parallel → backend and frontend in parallel → integration → QA with "
                        + "bounded remediation → security (conditional) and documentation → final review on the actual artifacts → "
                        + "approved handover.",
                m("inputs", l(m("name", "goal", "type", "text", "required", true, "description", "What to build")),
                        "steps", l(
                                step("requirements", "Extract requirements", "requirements-analyst", l(), m("goal", "${run.inputs.goal}")),
                                step("architecture", "Architecture and API contract", "solution-architect", l("requirements"),
                                        m("requirements", "${steps.requirements.output.requirements}")),
                                step("design", "Design specification", "ux-designer", l("requirements"),
                                        m("requirements", "${steps.requirements.output.requirements}")),
                                step("backend", "Backend implementation plan", "backend-engineer", l("architecture"),
                                        m("capabilities", "${steps.architecture.output.capabilities}",
                                                "database", "${steps.architecture.output.database}")),
                                step("frontend", "Frontend implementation plan", "frontend-engineer", l("architecture", "design"),
                                        m("screens", "${steps.design.output.screens}", "capabilities", "${steps.architecture.output.capabilities}")),
                                step("integration", "Integration check", "integration-engineer", l("backend", "frontend"),
                                        m("unmatchedScreens", "${steps.frontend.output.unmatchedCount}")),
                                with(step("security", "Security review", "security-reviewer", l("backend"),
                                                m("categories", "${steps.requirements.output.categories}")),
                                        "when", m("any", l(
                                                m("path", "steps.requirements.output.categories", "op", "contains", "value", "authentication"),
                                                m("path", "steps.requirements.output.categories", "op", "contains", "value", "payments"),
                                                m("path", "steps.requirements.output.categories", "op", "contains", "value", "security")))),
                                with(step("payments-compliance", "Payments compliance review", "security-reviewer", l("backend"),
                                                m("categories", "${steps.requirements.output.categories}")),
                                        "when", m("path", "steps.requirements.output.categories", "op", "contains", "value", "payments")),
                                with(with(step("qa", "QA: coverage and test plan", "qa-engineer", l("integration"),
                                                m("requirements", "${steps.requirements.output.requirements}")),
                                        "loop", m("until", m("path", "output.passed", "op", "eq", "value", true), "maxIterations", 3,
                                                "remediationStep", "fix-gaps")),
                                        "retry", m("maxAttempts", 3, "backoffSeconds", 1)),
                                with(step("fix-gaps", "Fix coverage gaps", "remediation-engineer", l(),
                                                m("missing", "${steps.qa.output.missing}", "requirements", "${steps.requirements.output.requirements}")),
                                        "remediation", true),
                                step("docs", "Documentation", "documentation-writer", l("integration"),
                                        m("capabilities", "${steps.architecture.output.capabilities}", "screens", "${steps.design.output.screens}",
                                                "requirements", "${steps.requirements.output.requirements}",
                                                "database", "${steps.architecture.output.database}")),
                                step("final-review", "Final review", "final-reviewer", l("qa", "security", "payments-compliance", "docs"),
                                        m("requirements", "${steps.requirements.output.requirements}", "qaPassed", "${steps.qa.output.passed}",
                                                "securityStatus", "${steps.security.status}")),
                                with(step("handover", "Approved handover", "release-manager", l("final-review"),
                                                m("verdict", "${steps.final-review.output.summary}")),
                                        "approval", m("required", true, "reason", "A person signs off before the delivery package is published"))),
                        "completion", m("requiredSteps", l("final-review", "qa", "handover"), "criteria", l(
                                m("name", "Final review passed on the actual artifacts",
                                        "condition", m("path", "steps.final-review.output.passed", "op", "eq", "value", true)),
                                m("name", "Every requirement is covered by the contract, the design or a gap resolution",
                                        "condition", m("path", "steps.qa.output.passed", "op", "eq", "value", true)),
                                m("name", "Security review passed or was not needed",
                                        "condition", m("path", "steps.security.status", "op", "in", "value", l("SUCCEEDED", "SKIPPED"))))),
                        "outputs", m("verdict", "${steps.final-review.output.summary}", "requirements", "${steps.requirements.output.count}",
                                "qaIterations", "${steps.qa.iteration}")));
    }

    public static Definition research() {
        return new Definition(RESEARCH, "Research and report",
                "Plan questions and collect recorded sources in parallel → analyse evidence → write a cited report → fact-check "
                        + "with bounded revision → editorial review.",
                m("inputs", l(m("name", "goal", "type", "text", "required", true, "description", "The research brief")),
                        "steps", l(
                                step("plan", "Plan research questions", "research-planner", l(), m("topic", "${run.inputs.goal}")),
                                step("collect", "Collect recorded sources", "source-collector", l(), m("topic", "${run.inputs.goal}")),
                                step("analyze", "Analyse evidence", "research-analyst", l("plan", "collect"),
                                        m("questions", "${steps.plan.output.questions}", "sources", "${steps.collect.output.sources}")),
                                step("write", "Write the report", "report-writer", l("analyze"),
                                        m("evidence", "${steps.analyze.output.evidence}", "themes", "${steps.analyze.output.themes}")),
                                with(step("fact-check", "Fact check", "fact-checker", l("write"),
                                                m("evidence", "${steps.analyze.output.evidence}",
                                                        "supportedCount", "${steps.analyze.output.supportedCount}")),
                                        "loop", m("until", m("path", "output.passed", "op", "eq", "value", true), "maxIterations", 2,
                                                "remediationStep", "revise")),
                                with(step("revise", "Disclose open questions", "report-reviser", l(),
                                        m("evidence", "${steps.analyze.output.evidence}")), "remediation", true),
                                step("edit", "Editorial review", "editor", l("fact-check"),
                                        m("factCheckPassed", "${steps.fact-check.output.passed}"))),
                        "completion", m("requiredSteps", l("edit"), "criteria", l(
                                m("name", "Every question is cited or disclosed", "condition",
                                        m("path", "steps.fact-check.output.passed", "op", "eq", "value", true)),
                                m("name", "Editorial review passed", "condition",
                                        m("path", "steps.edit.output.passed", "op", "eq", "value", true)))),
                        "outputs", m("verdict", "${steps.edit.output.summary}", "openQuestions", "${steps.fact-check.output.unsupported}")));
    }

    public static Definition classic() {
        return new Definition(CLASSIC, "Plan, build, test",
                "Memora 1.0's Planner → Developer → Tester sequence, rebuilt as an ordinary workflow over agent definitions.",
                m("inputs", l(m("name", "goal", "type", "text", "required", true)),
                        "steps", l(
                                step("plan", "Plan", "planner", l(), m("goal", "${run.inputs.goal}")),
                                step("build", "Build", "developer", l("plan"), m("requirements", "${steps.plan.output.requirements}")),
                                with(step("test", "Test", "tester", l("build"), m("requirements", "${steps.plan.output.requirements}")),
                                        "retry", m("maxAttempts", 3, "backoffSeconds", 1))),
                        "completion", m("requiredSteps", l("test"), "criteria", l(m("name", "Tests passed",
                                "condition", m("path", "steps.test.output.passed", "op", "eq", "value", true))))));
    }

    /** Fallback for goals no template recognises: analyse, then delegate the work to an external agent. */
    public static Definition general() {
        return new Definition(GENERAL, "Analyse and delegate",
                "Requirements analysis by Memora, then the work is delegated to an external agent through its inbox.",
                m("inputs", l(m("name", "goal", "type", "text", "required", true)),
                        "steps", l(
                                step("requirements", "Extract requirements", "requirements-analyst", l(), m("goal", "${run.inputs.goal}")),
                                step("work", "Do the work (external agent)", "external-developer", l("requirements"),
                                        m("requirements", "${steps.requirements.output.requirements}"))),
                        "completion", m("requiredSteps", l("work"))));
    }

    static Map<String, Object> step(String key, String name, String agent, List<Object> dependsOn, Map<String, Object> inputs) {
        return m("key", key, "name", name, "agent", agent, "dependsOn", dependsOn, "inputs", inputs);
    }

    static Map<String, Object> with(Map<String, Object> step, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(step);
        copy.put(key, value);
        return copy;
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
