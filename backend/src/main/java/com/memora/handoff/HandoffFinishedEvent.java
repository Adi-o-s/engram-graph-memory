package com.memora.handoff;

import java.util.Map;
import java.util.UUID;

/** Published when a handoff reaches COMPLETED, FAILED, DEAD_LETTER or REJECTED (after the change commits). */
public record HandoffFinishedEvent(UUID tenantId, UUID handoffId, HandoffStatus status, Map<String, Object> result,
                                   String reason) {
}
