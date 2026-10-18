package com.memora.orchestration.tool.builtin;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional local workspace for file and command tools. Both are off unless configured:
 * {@code MEMORA_WORKSPACE_ROOT} names a directory agents may read, and {@code memora.workspace.commands}
 * maps a command id to a fixed argument vector (no shell, no arguments from agents), e.g.
 * {@code memora.workspace.commands.unit-tests=./mvnw,-q,test}.
 */
@ConfigurationProperties(prefix = "memora.workspace")
public record WorkspaceProperties(String root, Map<String, List<String>> commands, Integer commandTimeoutSeconds,
                                  Integer maxReadBytes) {

    public WorkspaceProperties {
        commands = commands == null ? Map.of() : commands;
        commandTimeoutSeconds = commandTimeoutSeconds == null ? 120 : commandTimeoutSeconds;
        maxReadBytes = maxReadBytes == null ? 200_000 : maxReadBytes;
    }

    public boolean enabled() {
        return root != null && !root.isBlank();
    }
}
