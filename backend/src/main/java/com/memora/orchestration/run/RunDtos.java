package com.memora.orchestration.run;

import com.memora.orchestration.run.ArtifactService.ArtifactView;
import com.memora.orchestration.run.EventLog.EventView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class RunDtos {

    private RunDtos() {
    }

    /**
     * @param options {@code pacingMs} slows each step so people can watch parallel execution (demo aid, max 5 s);
     *                {@code faults} injects labelled failures: {"stepKey": {"failAttempts": 1, "retryable": true}}
     *                (admins only, recorded in the run, used to demonstrate retries)
     */
    public record StartRunRequest(UUID projectId, @Size(max = 20000) String goal, Map<String, Object> inputs,
                                  Integer version, Map<String, Object> options) {
    }

    public record ApprovalDecision(@Size(max = 2000) String note) {
    }

    public record CompleteStepRequest(Map<String, Object> output, @Size(max = 2000) String error, Boolean retryable) {
    }

    public record RunSummary(UUID id, String name, String goal, RunStatus status, UUID workflowId, int workflowVersion,
                             UUID projectId, Map<String, Long> stepCounts, Instant createdAt, Instant updatedAt,
                             Instant finishedAt) {
    }

    public record ApprovalView(UUID id, String status, String reason, UUID decidedByUserId, String decisionNote,
                               Instant createdAt, Instant decidedAt) {
    }

    public record StepView(UUID id, String key, int iteration, String name, String agentKey, String agentName,
                           Integer agentVersion, String executor, StepStatus status, List<String> dependsOn, int attempts,
                           int maxAttempts, Map<String, Object> input, Map<String, Object> output, String error,
                           String errorKind, Map<String, Object> readyReason, String leaseOwner, Instant leaseExpiresAt,
                           Instant nextAttemptAt, UUID taskId, UUID handoffId, Instant createdAt, Instant readyAt,
                           Instant startedAt, Instant finishedAt, Long durationMs, boolean remediation,
                           boolean latest, ApprovalView approval, boolean canRetry) {
    }

    public record RunView(UUID id, String name, String goal, RunStatus status, UUID workflowId, String workflowKey,
                          int workflowVersion, UUID projectId, Map<String, Object> inputs, Map<String, Object> options,
                          Map<String, Object> spec, Map<String, Object> completion, Map<String, Object> outputs,
                          UUID taskId, UUID startedByUserId, List<StepView> steps, Map<String, Long> stepCounts,
                          List<List<String>> layers, List<Map<String, Object>> agents, Instant createdAt,
                          Instant updatedAt, Instant finishedAt) {
    }

    public record ToolCallView(UUID id, UUID stepRunId, String toolId, String status, boolean sideEffects,
                               Map<String, Object> input, Map<String, Object> output, String error, int durationMs,
                               Instant createdAt) {
    }

    public record StepDetail(StepView step, List<EventView> events, List<ToolCallView> toolCalls, List<ArtifactView> artifacts) {
    }

    public record ProjectWorkspace(UUID projectId, String name, String description, List<RunSummary> runs,
                                   List<Map<String, Object>> decisions, List<Map<String, Object>> requirements,
                                   List<Map<String, Object>> lessons, List<ArtifactView> artifacts,
                                   List<Map<String, Object>> outstanding, List<Map<String, Object>> history) {
    }

    public record StartResult(UUID runId) {
    }

    public record Limits(@Min(1) @Max(200) int limit) {
    }
}
