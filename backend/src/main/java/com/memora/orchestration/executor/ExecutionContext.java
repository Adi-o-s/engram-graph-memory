package com.memora.orchestration.executor;

import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolException;
import com.memora.orchestration.tool.ToolGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything an executor may use while running one step: the pinned agent snapshot, the resolved step
 * input, a read-only view of the run ({@code run}, {@code steps}, {@code input}, {@code agent}, {@code step}
 * for templates) and a tool function that goes through the {@link ToolGateway}. Executors get no
 * repository, service or database connection.
 */
public final class ExecutionContext {

    public static final int MAX_TOOL_CALLS = 120;

    private final ToolContext toolContext;
    private final ToolGateway gateway;
    private final Map<String, Object> input;
    private final Map<String, Object> view;
    private final List<String> log = new ArrayList<>();
    private int toolCalls;

    public ExecutionContext(ToolContext toolContext, ToolGateway gateway, Map<String, Object> input, Map<String, Object> view) {
        this.toolContext = toolContext;
        this.gateway = gateway;
        this.input = input;
        this.view = view;
    }

    public AgentSnapshot agent() {
        return toolContext.agent();
    }

    public ToolContext toolContext() {
        return toolContext;
    }

    public Map<String, Object> input() {
        return input;
    }

    /** The template view: run, steps, input, agent, step. */
    public Map<String, Object> view() {
        return view;
    }

    public Map<String, Object> tool(String toolId, Map<String, Object> args) {
        if (++toolCalls > MAX_TOOL_CALLS) {
            throw ToolException.permanent("Step exceeded the limit of " + MAX_TOOL_CALLS + " tool calls");
        }
        return gateway.invoke(toolContext, toolId, args);
    }

    public int toolCalls() {
        return toolCalls;
    }

    public void log(String line) {
        if (log.size() < 300) {
            log.add(line);
        }
    }

    public List<String> log() {
        return log;
    }
}
