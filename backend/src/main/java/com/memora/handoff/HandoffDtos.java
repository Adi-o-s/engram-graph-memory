package com.memora.handoff;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HandoffDtos {

    private HandoffDtos() {
    }

    public record CreateHandoffRequest(
            @NotNull UUID taskId,
            UUID fromAgentId,
            @NotNull UUID toAgentId,
            @NotBlank @Size(max = 4000) String summary,
            @Size(max = 50) List<UUID> memoryRefs,
            @Size(max = 50) List<@Size(max = 1000) String> decisions,
            Map<String, Object> artifacts,
            @NotBlank @Size(max = 2000) String nextAction,
            Long expectedTaskVersion,
            @Min(1) @Max(10) Integer maxAttempts) {
    }

    public record CompleteHandoffRequest(Map<String, Object> result) {
    }

    public record FailHandoffRequest(@NotBlank @Size(max = 2000) String reason, Map<String, Object> result) {
    }

    public record RejectHandoffRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record HandoffView(UUID id, UUID taskId, UUID fromAgentId, UUID toAgentId, String summary,
                              List<UUID> memoryRefs, List<String> decisions, Map<String, Object> artifacts,
                              String nextAction, HandoffStatus status, long taskVersion, int attempts, int maxAttempts,
                              String failureReason, Map<String, Object> result, Instant leaseExpiresAt,
                              UUID claimedByAgentId, long version, Instant createdAt, Instant acknowledgedAt,
                              Instant completedAt, Instant updatedAt) {
        public static HandoffView of(Handoff h) {
            return new HandoffView(h.getId(), h.getTaskId(), h.getFromAgentId(), h.getToAgentId(), h.getSummary(),
                    Arrays.asList(h.getMemoryRefs()), h.getDecisions(), h.getArtifacts(), h.getNextAction(),
                    h.getStatus(), h.getTaskVersion(), h.getAttempts(), h.getMaxAttempts(), h.getFailureReason(),
                    h.getResult(), h.getLeaseExpiresAt(), h.getClaimedByAgentId(),
                    h.getVersion() == null ? 0 : h.getVersion(), h.getCreatedAt(), h.getAcknowledgedAt(),
                    h.getCompletedAt(), h.getUpdatedAt());
        }
    }
}
