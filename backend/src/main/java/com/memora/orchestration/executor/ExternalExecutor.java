package com.memora.orchestration.executor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Delegates the step to whoever holds the agent's API key: a person, a script, or an AI coding agent
 * running on its own machine. The engine sends a handoff to the agent's inbox and keeps the step
 * RUNNING until the agent completes or fails that handoff (or calls the step-complete endpoint), or the
 * step's timeout expires. Memora itself does no reasoning here and never calls a model.
 */
@Component
public class ExternalExecutor implements AgentExecutor {

    public static final String ID = "external";

    @Override
    public ExecutorDescriptor descriptor() {
        return new ExecutorDescriptor(ID, "External agent (via handoff)",
                "Sends the step to the agent's inbox as a handoff. Work happens outside Memora; the agent reports "
                        + "its output by completing the handoff with {\"output\": {...}}. Use this for real code changes "
                        + "or anything that needs judgement.", false, true, true, null);
    }

    @Override
    public List<String> validateBehavior(Map<String, Object> behavior, Set<String> permittedTools) {
        List<String> errors = new ArrayList<>();
        if (behavior != null) {
            behavior.keySet().stream().filter(k -> !k.equals("brief") && !k.equals("notes"))
                    .forEach(k -> errors.add("behavior: unknown setting '" + k + "' for the external executor (allowed: brief, notes)"));
        }
        return errors;
    }

    @Override
    public StepOutcome execute(ExecutionContext context) {
        context.log("Dispatched to " + context.agent().agentName() + "'s inbox; waiting for the agent to complete the handoff");
        return StepOutcome.waitingExternal(context.log());
    }
}
