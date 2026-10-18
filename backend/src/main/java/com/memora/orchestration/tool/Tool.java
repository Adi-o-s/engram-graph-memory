package com.memora.orchestration.tool;

import java.util.Map;

/**
 * A capability the platform exposes to agents. Tools are the only way an executor can read or change
 * anything; they receive a {@link ToolContext} that pins the tenant, project, run and agent, so a tool
 * can never be pointed at another tenant's data by its arguments.
 */
public interface Tool {

    ToolDescriptor descriptor();

    /** Runs the call. Arguments have already been validated against the input schema. */
    Map<String, Object> invoke(ToolContext context, Map<String, Object> args);
}
