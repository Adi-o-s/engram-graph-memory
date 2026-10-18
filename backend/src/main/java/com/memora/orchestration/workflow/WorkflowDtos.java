package com.memora.orchestration.workflow;

import com.memora.orchestration.workflow.WorkflowValidator.Analysis;
import com.memora.orchestration.workflow.WorkflowValidator.Problem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkflowDtos {

    private WorkflowDtos() {
    }

    public record WorkflowRequest(@Size(max = 64) String key, @NotBlank @Size(max = 200) String name,
                                  @Size(max = 4000) String description, UUID projectId, @NotNull Map<String, Object> spec,
                                  @Size(max = 500) String changeNote, Long expectedLockVersion) {
    }

    public record ValidateRequest(@NotNull Map<String, Object> spec) {
    }

    public record DuplicateRequest(@Size(max = 64) String key, @Size(max = 200) String name) {
    }

    public record ValidationView(boolean valid, List<Problem> errors, List<String> warnings, Analysis analysis) {
        static ValidationView of(WorkflowValidator.Result r) {
            return new ValidationView(r.valid(), r.errors(), r.warnings(), r.analysis());
        }
    }

    public record WorkflowView(UUID id, String key, String name, String description, UUID projectId, int version,
                               String status, long lockVersion, Map<String, Object> spec, int stepCount,
                               List<String> agents, ValidationView validation, Instant createdAt, Instant updatedAt) {
    }

    public record VersionView(int version, Map<String, Object> spec, String changeNote, UUID createdByUserId,
                              Instant createdAt) {
    }
}
