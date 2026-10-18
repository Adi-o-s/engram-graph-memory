package com.memora.memory;

/**
 * Where a memory applies. TENANT, PROJECT and USER are the original scopes. WORKFLOW (lessons shared by
 * every run of one workflow), RUN (working memory of one workflow execution) and AGENT (an agent's own
 * reusable procedures) were added for orchestration; they need a {@code scopeRef} naming that entity.
 */
public enum MemoryScope {
    TENANT, PROJECT, USER, WORKFLOW, RUN, AGENT;

    public boolean needsRef() {
        return this == WORKFLOW || this == RUN || this == AGENT;
    }
}
