package com.memora.orchestration.tool;

import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.security.Actor;
import java.util.UUID;

/**
 * The fixed scope of every tool call made while executing one step. Built by the engine from persisted
 * run state, never from tool arguments.
 *
 * @param actor          the agent's principal in the run's tenant (memory provenance, handoffs)
 * @param startedByUserId the user who started the run; USER-scoped memory is only ever theirs
 * @param approved       whether the step passed a human approval gate
 */
public record ToolContext(UUID tenantId, Actor actor, UUID projectId, UUID workflowId, UUID runId, UUID stepRunId,
                          String stepKey, int iteration, UUID startedByUserId, AgentSnapshot agent, boolean approved) {
}
