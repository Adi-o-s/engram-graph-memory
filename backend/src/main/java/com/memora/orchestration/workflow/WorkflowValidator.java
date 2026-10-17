package com.memora.orchestration.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.orchestration.expr.Conditions;
import com.memora.orchestration.expr.Expressions;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolRegistry;
import com.memora.orchestration.workflow.WorkflowSpec.CriterionSpec;
import com.memora.orchestration.workflow.WorkflowSpec.InputSpec;
import com.memora.orchestration.workflow.WorkflowSpec.StepSpec;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates a workflow spec before it is saved and before it runs: structure, unknown settings, agent
 * references, dependency graph (unknown steps, self-dependencies, cycles), bounded loops, conditions and
 * data references (a step may only read outputs of steps it transitively depends on), approval rules for
 * tools that need them, and completion criteria. Also computes the execution layers that show which
 * steps can run in parallel.
 */
@Component
public class WorkflowValidator {

    public static final Pattern STEP_KEY = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final Pattern INPUT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final Set<String> TOP_KEYS = Set.of("inputs", "steps", "completion", "outputs", "notes");
    private static final Set<String> STEP_KEYS = Set.of("key", "name", "description", "agent", "dependsOn", "inputs", "when",
            "retry", "timeoutSeconds", "approval", "loop", "remediation", "mandatory");
    private static final Set<String> INPUT_TYPES = Set.of("string", "text", "number", "boolean", "object", "array");
    private static final Set<String> RUN_FIELDS = Set.of("id", "goal", "name", "projectId", "inputs", "workflowId");
    public static final int MAX_STEPS = 60;

    private final ObjectMapper objectMapper;
    private final ToolRegistry tools;

    public WorkflowValidator(ObjectMapper objectMapper, ToolRegistry tools) {
        this.objectMapper = objectMapper;
        this.tools = tools;
    }

    public record Problem(String path, String message) {
    }

    public record Analysis(List<List<String>> layers, List<List<String>> parallelGroups, List<String> roots,
                           List<String> leaves, List<String> order, List<String> remediationSteps,
                           Map<String, List<String>> dependents) {
    }

    public record Result(boolean valid, List<Problem> errors, List<String> warnings, Analysis analysis, WorkflowSpec spec) {
    }

    /**
     * @param agents the tenant's agent definitions by key (status checked only when {@code requireActive})
     */
    @SuppressWarnings("unchecked")
    public Result validate(Map<String, Object> raw, Map<String, AgentSnapshot> agents, Map<String, String> agentStatus,
                           boolean requireActive) {
        List<Problem> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (raw == null) {
            errors.add(new Problem("spec", "is required"));
            return new Result(false, errors, warnings, null, null);
        }
        raw.keySet().stream().filter(k -> !TOP_KEYS.contains(k)).forEach(k -> errors.add(new Problem(k, "unknown setting")));
        if (raw.get("steps") instanceof Collection<?> rawSteps) {
            int i = 0;
            for (Object s : rawSteps) {
                if (s instanceof Map<?, ?> m) {
                    for (Object k : m.keySet()) {
                        if (!STEP_KEYS.contains(String.valueOf(k))) {
                            errors.add(new Problem("steps[" + i + "]." + k, "unknown setting"));
                        }
                    }
                } else {
                    errors.add(new Problem("steps[" + i + "]", "must be an object"));
                }
                i++;
            }
        }
        WorkflowSpec spec;
        try {
            spec = objectMapper.convertValue(raw, WorkflowSpec.class);
        } catch (IllegalArgumentException e) {
            errors.add(new Problem("spec", "has the wrong shape: " + rootMessage(e)));
            return new Result(false, errors, warnings, null, null);
        }

        // Inputs
        Set<String> inputNames = new HashSet<>();
        int ii = 0;
        for (InputSpec in : spec.inputsOrEmpty()) {
            String at = "inputs[" + ii++ + "]";
            if (in.name() == null || !INPUT_NAME.matcher(in.name()).matches()) {
                errors.add(new Problem(at + ".name", "must be letters, digits or underscores"));
            } else if (!inputNames.add(in.name())) {
                errors.add(new Problem(at + ".name", "'" + in.name() + "' is declared twice"));
            }
            if (in.type() != null && !INPUT_TYPES.contains(in.type())) {
                errors.add(new Problem(at + ".type", "must be one of " + INPUT_TYPES));
            }
        }

        // Steps: keys, agents, settings
        List<StepSpec> steps = spec.stepsOrEmpty();
        if (steps.isEmpty()) {
            errors.add(new Problem("steps", "a workflow needs at least one step"));
        }
        if (steps.size() > MAX_STEPS) {
            errors.add(new Problem("steps", "at most " + MAX_STEPS + " steps are allowed"));
        }
        Map<String, StepSpec> byKey = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            StepSpec s = steps.get(i);
            String at = "steps[" + i + "]";
            if (s.key() == null || !STEP_KEY.matcher(s.key()).matches()) {
                errors.add(new Problem(at + ".key", "must be lowercase letters, digits and hyphens, starting with a letter"));
                continue;
            }
            if (byKey.put(s.key(), s) != null) {
                errors.add(new Problem(at + ".key", "'" + s.key() + "' is used by two steps"));
            }
            if (s.agent() == null || s.agent().isBlank()) {
                errors.add(new Problem(at + ".agent", "every step must be assigned to an agent definition"));
            } else if (!agents.containsKey(s.agent())) {
                errors.add(new Problem(at + ".agent", "no agent definition with key '" + s.agent() + "'"));
            } else if (requireActive && !"ACTIVE".equals(agentStatus.get(s.agent()))) {
                errors.add(new Problem(at + ".agent", "agent '" + s.agent() + "' is inactive"));
            } else if (!"ACTIVE".equals(agentStatus.get(s.agent()))) {
                warnings.add("Step " + s.key() + " uses inactive agent '" + s.agent() + "'; activate it before running.");
            }
            if (s.retry() != null) {
                if (s.retry().maxAttempts() != null && (s.retry().maxAttempts() < 1 || s.retry().maxAttempts() > 10)) {
                    errors.add(new Problem(at + ".retry.maxAttempts", "must be between 1 and 10"));
                }
                if (s.retry().backoffSeconds() != null && (s.retry().backoffSeconds() < 0 || s.retry().backoffSeconds() > 3600)) {
                    errors.add(new Problem(at + ".retry.backoffSeconds", "must be between 0 and 3600"));
                }
            }
            if (s.timeoutSeconds() != null && (s.timeoutSeconds() < 1 || s.timeoutSeconds() > 86400)) {
                errors.add(new Problem(at + ".timeoutSeconds", "must be between 1 and 86400"));
            }
            if (s.deps().size() > 20) {
                errors.add(new Problem(at + ".dependsOn", "at most 20 dependencies"));
            }
            Expressions.syntaxErrors(s.inputs()).forEach(m -> errors.add(new Problem(at + ".inputs", m)));
            AgentSnapshot agent = s.agent() == null ? null : agents.get(s.agent());
            if (agent != null && !s.needsApproval()) {
                for (String toolId : agent.tools()) {
                    tools.find(toolId).map(t -> t.descriptor()).filter(ToolDescriptor::requiresApproval).ifPresent(d ->
                            errors.add(new Problem(at + ".approval", "agent '" + s.agent() + "' may use " + d.id()
                                    + ", which requires an approval gate: set approval.required to true")));
                }
            }
        }

        // Dependencies
        Set<String> remediationKeys = new LinkedHashSet<>();
        for (StepSpec s : byKey.values()) {
            if (s.isRemediation()) {
                remediationKeys.add(s.key());
            }
        }
        for (StepSpec s : byKey.values()) {
            for (String d : s.deps()) {
                String at = "steps." + s.key() + ".dependsOn";
                if (d.equals(s.key())) {
                    errors.add(new Problem(at, "a step cannot depend on itself"));
                } else if (!byKey.containsKey(d)) {
                    errors.add(new Problem(at, "unknown step '" + d + "'"));
                } else if (remediationKeys.contains(d)) {
                    errors.add(new Problem(at, "'" + d + "' is a remediation step; it only runs inside its loop"));
                }
            }
            if (s.isRemediation() && !s.deps().isEmpty()) {
                errors.add(new Problem("steps." + s.key() + ".dependsOn", "a remediation step runs after its loop step; leave dependsOn empty"));
            }
            if (s.isRemediation() && s.loop() != null) {
                errors.add(new Problem("steps." + s.key() + ".loop", "a remediation step cannot itself loop"));
            }
        }

        // Loops
        Map<String, String> remediationOwner = new HashMap<>();
        for (StepSpec s : byKey.values()) {
            if (s.loop() == null) {
                continue;
            }
            String at = "steps." + s.key() + ".loop";
            if (s.loop().max() < 1 || s.loop().max() > 5) {
                errors.add(new Problem(at + ".maxIterations", "must be between 1 and 5 (loops are always bounded)"));
            }
            if (s.loop().until() == null || s.loop().until().isEmpty()) {
                errors.add(new Problem(at + ".until", "a loop needs an exit condition"));
            }
            String rem = s.loop().remediationStep();
            if (rem == null || !byKey.containsKey(rem)) {
                errors.add(new Problem(at + ".remediationStep", "must name an existing step"));
            } else if (!byKey.get(rem).isRemediation()) {
                errors.add(new Problem(at + ".remediationStep", "step '" + rem + "' must set remediation: true"));
            } else if (remediationOwner.put(rem, s.key()) != null) {
                errors.add(new Problem(at + ".remediationStep", "step '" + rem + "' is already the remediation of another loop"));
            }
        }
        for (String rem : remediationKeys) {
            if (!remediationOwner.containsKey(rem)) {
                errors.add(new Problem("steps." + rem, "remediation step is not referenced by any loop and would never run"));
            }
        }

        // Cycles and layers (remediation steps are outside the graph)
        List<String> graphKeys = byKey.keySet().stream().filter(k -> !remediationKeys.contains(k)).toList();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        graphKeys.forEach(k -> dependents.put(k, new ArrayList<>()));
        Map<String, Integer> indegree = new HashMap<>();
        for (String k : graphKeys) {
            int n = 0;
            for (String d : byKey.get(k).deps()) {
                if (dependents.containsKey(d)) {
                    dependents.get(d).add(k);
                    n++;
                }
            }
            indegree.put(k, n);
        }
        List<List<String>> layers = new ArrayList<>();
        List<String> order = new ArrayList<>();
        Map<String, Integer> remaining = new HashMap<>(indegree);
        List<String> current = graphKeys.stream().filter(k -> remaining.get(k) == 0).toList();
        while (!current.isEmpty()) {
            layers.add(current);
            order.addAll(current);
            List<String> next = new ArrayList<>();
            for (String k : current) {
                for (String child : dependents.get(k)) {
                    if (remaining.merge(child, -1, Integer::sum) == 0) {
                        next.add(child);
                    }
                }
            }
            current = next;
        }
        if (order.size() < graphKeys.size()) {
            List<String> cyclic = graphKeys.stream().filter(k -> !order.contains(k)).toList();
            errors.add(new Problem("steps", "dependency cycle among " + cyclic
                    + ". Workflows must be acyclic; use a bounded loop (loop + remediation step) for iteration."));
        }

        // References from templates and conditions
        Map<String, Set<String>> ancestors = new HashMap<>();
        for (String k : order) {
            Set<String> a = new LinkedHashSet<>();
            for (String d : byKey.get(k).deps()) {
                if (byKey.containsKey(d)) {
                    a.add(d);
                    a.addAll(ancestors.getOrDefault(d, Set.of()));
                }
            }
            ancestors.put(k, a);
        }
        for (String rem : remediationKeys) {
            String owner = remediationOwner.get(rem);
            if (owner != null) {
                Set<String> a = new LinkedHashSet<>(ancestors.getOrDefault(owner, Set.of()));
                a.add(owner);
                ancestors.put(rem, a);
            }
        }
        for (StepSpec s : byKey.values()) {
            Set<String> visible = new HashSet<>(ancestors.getOrDefault(s.key(), Set.of()));
            if (s.loop() != null && s.loop().remediationStep() != null) {
                visible.add(s.loop().remediationStep());
            }
            for (String path : Expressions.paths(s.inputs())) {
                checkPath(path, "steps." + s.key() + ".inputs", visible, inputNames, Set.of("run", "steps"), errors);
            }
            if (s.when() != null) {
                List<String> paths = new ArrayList<>();
                List<String> condErrors = new ArrayList<>();
                Conditions.validate(s.when(), "steps." + s.key() + ".when", condErrors, paths);
                condErrors.forEach(m -> errors.add(new Problem("steps." + s.key() + ".when", m)));
                paths.forEach(p -> checkPath(p, "steps." + s.key() + ".when", visible, inputNames, Set.of("run", "steps"), errors));
            }
            if (s.loop() != null && s.loop().until() != null) {
                List<String> paths = new ArrayList<>();
                List<String> condErrors = new ArrayList<>();
                Conditions.validate(s.loop().until(), "steps." + s.key() + ".loop.until", condErrors, paths);
                condErrors.forEach(m -> errors.add(new Problem("steps." + s.key() + ".loop.until", m)));
                paths.forEach(p -> checkPath(p, "steps." + s.key() + ".loop.until", visible, inputNames,
                        Set.of("run", "steps", "output"), errors));
            }
        }

        // Completion and outputs
        Set<String> all = byKey.keySet();
        if (spec.completion() != null) {
            for (String r : spec.completion().required()) {
                if (!all.contains(r)) {
                    errors.add(new Problem("completion.requiredSteps", "unknown step '" + r + "'"));
                }
            }
            int ci = 0;
            for (CriterionSpec c : spec.completion().criteriaOrEmpty()) {
                String at = "completion.criteria[" + ci++ + "]";
                if (c.name() == null || c.name().isBlank()) {
                    errors.add(new Problem(at + ".name", "is required"));
                }
                List<String> paths = new ArrayList<>();
                List<String> condErrors = new ArrayList<>();
                Conditions.validate(c.condition(), at + ".condition", condErrors, paths);
                if (c.condition() == null || c.condition().isEmpty()) {
                    condErrors.add(at + ".condition: is required");
                }
                condErrors.forEach(m -> errors.add(new Problem(at, m)));
                paths.forEach(p -> checkPath(p, at, all, inputNames, Set.of("run", "steps"), errors));
            }
        } else {
            warnings.add("No completion criteria: the run succeeds when every mandatory step succeeds or is skipped.");
        }
        Expressions.syntaxErrors(spec.outputs()).forEach(m -> errors.add(new Problem("outputs", m)));
        for (String p : Expressions.paths(spec.outputs())) {
            checkPath(p, "outputs", all, inputNames, Set.of("run", "steps"), errors);
        }
        if (spec.completion() == null || spec.completion().criteriaOrEmpty().isEmpty()) {
            if (spec.completion() != null) {
                warnings.add("Completion has no criteria; only step statuses decide success.");
            }
        }

        List<String> roots = graphKeys.stream().filter(k -> byKey.get(k).deps().isEmpty()).toList();
        List<String> leaves = graphKeys.stream().filter(k -> dependents.getOrDefault(k, List.of()).isEmpty()).toList();
        Analysis analysis = new Analysis(layers, layers.stream().filter(l -> l.size() > 1).toList(), roots, leaves, order,
                List.copyOf(remediationKeys), dependents);
        return new Result(errors.isEmpty(), errors, warnings, analysis, spec);
    }

    private static void checkPath(String path, String at, Set<String> visibleSteps, Set<String> inputNames, Set<String> roots,
                                  List<Problem> errors) {
        String[] parts = path.split("\\.");
        String root = parts[0];
        if (!roots.contains(root)) {
            errors.add(new Problem(at, "${" + path + "}: references must start with " + roots));
            return;
        }
        if (root.equals("run")) {
            if (parts.length < 2 || !RUN_FIELDS.contains(parts[1])) {
                errors.add(new Problem(at, "${" + path + "}: run has " + RUN_FIELDS));
            } else if (parts[1].equals("inputs") && parts.length > 2 && !inputNames.contains(parts[2])) {
                errors.add(new Problem(at, "${" + path + "}: workflow input '" + parts[2] + "' is not declared"));
            }
        } else if (root.equals("steps")) {
            if (parts.length < 2) {
                errors.add(new Problem(at, "${" + path + "}: name a step, e.g. steps.review.output"));
            } else if (!visibleSteps.contains(parts[1])) {
                errors.add(new Problem(at, "${" + path + "}: step '" + parts[1]
                        + "' is not an upstream dependency here, so its output is not guaranteed to exist"));
            } else if (parts.length > 2 && !Set.of("output", "status", "iteration", "attempts").contains(parts[2])) {
                errors.add(new Problem(at, "${" + path + "}: a step exposes output, status, iteration and attempts"));
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? t.toString() : t.getMessage();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    /** Helper for the UI and tests: the transitive dependents of a step. */
    public static Set<String> downstream(Map<String, List<String>> dependents, String key) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(dependents.getOrDefault(key, List.of()));
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (out.add(k)) {
                queue.addAll(dependents.getOrDefault(k, List.of()));
            }
        }
        return out;
    }
}
