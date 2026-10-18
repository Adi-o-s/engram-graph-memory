package com.memora.orchestration.executor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ExecutorRegistry {

    /** Documented extension point, deliberately not shipped: Memora must run without any paid model. */
    public static final ExecutorDescriptor MODEL_PLACEHOLDER = new ExecutorDescriptor("model",
            "Model-backed reasoning (not included)",
            "Would let an agent plan and write free-form output with a language model. Not shipped: it would need a "
                    + "runtime model service. Implement AgentExecutor to add one; the engine needs no change.",
            false, false, false, "Requires a runtime language model, which this zero-cost build does not include");

    private final Map<String, AgentExecutor> executors = new LinkedHashMap<>();

    public ExecutorRegistry(List<AgentExecutor> all) {
        all.forEach(e -> executors.put(e.descriptor().id(), e));
    }

    public Optional<AgentExecutor> find(String id) {
        return Optional.ofNullable(executors.get(id));
    }

    public List<ExecutorDescriptor> descriptors() {
        List<ExecutorDescriptor> out = new ArrayList<>(executors.values().stream().map(AgentExecutor::descriptor).toList());
        out.add(MODEL_PLACEHOLDER);
        return out;
    }
}
