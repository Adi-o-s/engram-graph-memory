package com.memora.orchestration.tool.builtin;

import com.memora.memory.MemoryScope;
import com.memora.orchestration.tool.ToolContext;
import java.util.UUID;

/** Resolves a memory scope to the concrete scope key for the run in a tool context. */
public final class ScopeKeys {

    private ScopeKeys() {
    }

    /** Null when the scope does not apply to this run (e.g. USER for a run with no human starter). */
    public static String keyFor(MemoryScope scope, ToolContext ctx) {
        return switch (scope) {
            case TENANT -> "tenant";
            case PROJECT -> ctx.projectId() == null ? null : "project:" + ctx.projectId();
            case USER -> ctx.startedByUserId() == null ? null : "user:" + ctx.startedByUserId();
            case WORKFLOW -> ctx.workflowId() == null ? null : "workflow:" + ctx.workflowId();
            case RUN -> ctx.runId() == null ? null : "run:" + ctx.runId();
            case AGENT -> ctx.agent().agentId() == null ? null : "agent:" + ctx.agent().agentId();
        };
    }

    /** The id MemoryService needs for WORKFLOW, RUN and AGENT scopes. */
    public static UUID refFor(MemoryScope scope, ToolContext ctx) {
        return switch (scope) {
            case WORKFLOW -> ctx.workflowId();
            case RUN -> ctx.runId();
            case AGENT -> ctx.agent().agentId();
            default -> null;
        };
    }
}
