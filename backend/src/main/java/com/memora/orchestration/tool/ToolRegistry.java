package com.memora.orchestration.tool;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** All tools known to this build, by id. Tools are Spring beans, so adding one needs no registry change. */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> all) {
        all.stream().sorted(Comparator.comparing(t -> t.descriptor().id()))
                .forEach(t -> {
                    if (tools.put(t.descriptor().id(), t) != null) {
                        throw new IllegalStateException("Duplicate tool id " + t.descriptor().id());
                    }
                });
    }

    public Optional<Tool> find(String id) {
        return Optional.ofNullable(tools.get(id));
    }

    public List<ToolDescriptor> descriptors() {
        return tools.values().stream().map(Tool::descriptor).toList();
    }

    public boolean exists(String id) {
        return tools.containsKey(id);
    }
}
