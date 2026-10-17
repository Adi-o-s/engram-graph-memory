package com.memora.orchestration.agentdef;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable copy of an agent definition at one version. A run pins the snapshots it started with, so
 * editing an agent later never changes what an in-flight run is allowed to do.
 */
public record AgentSnapshot(
        UUID definitionId,
        String key,
        String name,
        String role,
        String description,
        String instructions,
        List<String> capabilities,
        List<String> tools,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> memoryPolicy,
        Map<String, Object> contextPolicy,
        String executor,
        Map<String, Object> behavior,
        int timeoutSeconds,
        int maxAttempts,
        int backoffSeconds,
        int version,
        UUID agentId,
        String agentName) {

    public MemoryPolicy policy() {
        return MemoryPolicy.of(memoryPolicy);
    }
}
