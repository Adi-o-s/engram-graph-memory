package com.memora.orchestration.planning;

import com.memora.security.Actor;
import java.util.List;
import java.util.Map;

/**
 * Turns a goal into a structured, editable workflow proposal. The shipped implementation is rule-based; a
 * model-backed planner could implement the same interface without any change to the engine, because the
 * proposal is just a workflow spec plus explanations that a person reviews before anything runs.
 */
public interface WorkflowPlanner {

    Proposal propose(Actor actor, String goal);

    record Proposal(
            String planner,
            String goal,
            String subject,
            Map<String, Object> domain,
            List<Map<String, Object>> requirements,
            List<String> deliverables,
            List<Map<String, Object>> roles,
            List<Map<String, Object>> steps,
            List<List<String>> parallelGroups,
            List<Map<String, Object>> acceptanceCriteria,
            List<String> unclear,
            List<String> missingInformation,
            List<String> assumptions,
            List<String> approvals,
            List<String> limitations,
            List<String> warnings,
            String suggestedName,
            String suggestedKey,
            Map<String, Object> spec) {
    }
}
