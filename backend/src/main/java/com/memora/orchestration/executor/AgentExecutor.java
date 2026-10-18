package com.memora.orchestration.executor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The generic execution interface. The engine calls the same method for every agent, whatever its role;
 * only the agent definition's {@code executor} and {@code behavior} decide what happens. A future
 * model-backed executor would implement this interface without any change to the engine.
 */
public interface AgentExecutor {

    ExecutorDescriptor descriptor();

    /** Checks an agent's {@code behavior} configuration for this executor. */
    List<String> validateBehavior(Map<String, Object> behavior, Set<String> permittedTools);

    StepOutcome execute(ExecutionContext context);
}
