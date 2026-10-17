package com.memora.orchestration.agentdef;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AgentDefinitionDtos {

    private AgentDefinitionDtos() {
    }

    /** Create or update. On update {@code expectedLockVersion} is required (optimistic locking). */
    public record AgentDefinitionRequest(
            @Size(max = 64) String key,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 2000) String description,
            @NotBlank @Size(max = 100) String role,
            @Size(max = 20000) String instructions,
            @Size(max = 30) List<@Size(max = 60) String> capabilities,
            @Size(max = 30) List<@Size(max = 60) String> tools,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            Map<String, Object> memoryPolicy,
            Map<String, Object> contextPolicy,
            @NotBlank @Size(max = 40) String executor,
            Map<String, Object> behavior,
            @Min(1) @Max(86400) Integer timeoutSeconds,
            @Min(1) @Max(10) Integer maxAttempts,
            @Min(0) @Max(3600) Integer backoffSeconds,
            Long expectedLockVersion) {
    }

    public record DuplicateRequest(@Size(max = 64) String key, @Size(max = 100) String name) {
    }

    public record Problem(String field, String message) {
    }

    public record ValidationReport(boolean valid, List<Problem> errors, List<String> warnings) {
    }

    public record AgentDefinitionView(
            UUID id, String key, String name, String description, String role, String instructions,
            List<String> capabilities, List<String> tools, Map<String, Object> inputSchema, Map<String, Object> outputSchema,
            Map<String, Object> memoryPolicy, Map<String, Object> contextPolicy, String executor,
            Map<String, Object> behavior, int timeoutSeconds, int maxAttempts, int backoffSeconds, String status,
            int version, long lockVersion, String sourceTemplate, UUID agentId, String agentName, boolean agentHasApiKey,
            Instant createdAt, Instant updatedAt) {

        static AgentDefinitionView of(AgentDefinition d, String agentName, boolean hasKey) {
            return new AgentDefinitionView(d.getId(), d.getKey(), d.getName(), d.getDescription(), d.getRole(),
                    d.getInstructions(), d.getCapabilities(), d.getTools(), d.getInputSchema(), d.getOutputSchema(),
                    d.getMemoryPolicy(), d.getContextPolicy(), d.getExecutor(), d.getBehavior(), d.getTimeoutSeconds(),
                    d.getMaxAttempts(), d.getBackoffSeconds(), d.getStatus(), d.getRevision(),
                    d.getLockVersion() == null ? 0 : d.getLockVersion(), d.getSourceTemplate(), d.getAgentId(), agentName,
                    hasKey, d.getCreatedAt(), d.getUpdatedAt());
        }
    }

    public record VersionView(int version, Map<String, Object> snapshot, UUID createdByUserId, Instant createdAt) {
    }

    /** A read-only system template. Installing it creates a tenant-owned, editable definition. */
    public record TemplateView(String key, String name, String role, String description, String category,
                               List<String> capabilities, List<String> tools, String executor, boolean installed,
                               AgentDefinitionRequest definition) {
    }
}
