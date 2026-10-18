package com.memora.orchestration.tool;

import java.util.List;
import java.util.Map;

/**
 * What a tool declares about itself. The gateway enforces {@code requiredCapability},
 * {@code requiresApproval} and {@code inputSchema}; {@code sideEffects} decides whether a call is also
 * written to the audit trail.
 */
public record ToolDescriptor(
        String id,
        String title,
        String description,
        String category,
        String requiredCapability,
        boolean sideEffects,
        boolean requiresApproval,
        int timeoutMs,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<String> constraints,
        String failureBehavior,
        boolean available,
        String unavailableReason) {
}
