package com.memora.orchestration.workflow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A workflow version: a directed acyclic graph of steps, each assigned to an agent definition by key.
 *
 * <ul>
 *   <li>{@code dependsOn}: a step becomes READY when every dependency SUCCEEDED or was SKIPPED;</li>
 *   <li>{@code inputs}: a template over {@code run.*} and {@code steps.<key>.output.*} of its ancestors;</li>
 *   <li>{@code when}: a condition; false means SKIPPED (conditional routing);</li>
 *   <li>{@code loop}: bounded iteration: after the step succeeds, while {@code until} is false and fewer than
 *       {@code maxIterations} have run, the remediation step runs and then this step runs again;</li>
 *   <li>{@code approval.required}: the step waits for a person before it may run;</li>
 *   <li>{@code completion}: what must be true for the run to count as SUCCEEDED.</li>
 * </ul>
 */
public record WorkflowSpec(List<InputSpec> inputs, List<StepSpec> steps, CompletionSpec completion,
                           Map<String, Object> outputs) {

    public List<InputSpec> inputsOrEmpty() {
        return inputs == null ? List.of() : inputs;
    }

    public List<StepSpec> stepsOrEmpty() {
        return steps == null ? List.of() : steps;
    }

    public Optional<StepSpec> step(String key) {
        return stepsOrEmpty().stream().filter(s -> key.equals(s.key())).findFirst();
    }

    public record InputSpec(String name, String type, Boolean required, String description, Object defaultValue) {
        public boolean isRequired() {
            return Boolean.TRUE.equals(required);
        }
    }

    public record StepSpec(String key, String name, String description, String agent, List<String> dependsOn,
                           Map<String, Object> inputs, Map<String, Object> when, RetrySpec retry, Integer timeoutSeconds,
                           ApprovalSpec approval, LoopSpec loop, Boolean remediation, Boolean mandatory) {

        public List<String> deps() {
            return dependsOn == null ? List.of() : dependsOn;
        }

        public boolean isRemediation() {
            return Boolean.TRUE.equals(remediation);
        }

        public boolean isMandatory() {
            return mandatory == null || mandatory;
        }

        public boolean needsApproval() {
            return approval != null && Boolean.TRUE.equals(approval.required());
        }

        public String displayName() {
            return name == null || name.isBlank() ? key : name;
        }
    }

    public record RetrySpec(Integer maxAttempts, Integer backoffSeconds) {
    }

    public record ApprovalSpec(Boolean required, String reason) {
    }

    public record LoopSpec(Map<String, Object> until, Integer maxIterations, String remediationStep) {
        public int max() {
            return maxIterations == null ? 3 : maxIterations;
        }
    }

    public record CompletionSpec(List<String> requiredSteps, List<CriterionSpec> criteria) {
        public List<String> required() {
            return requiredSteps == null ? List.of() : requiredSteps;
        }

        public List<CriterionSpec> criteriaOrEmpty() {
            return criteria == null ? List.of() : criteria;
        }
    }

    public record CriterionSpec(String name, String description, Map<String, Object> condition) {
    }
}
