package com.memora.orchestration.agentdef;

import com.memora.orchestration.agentdef.AgentDefinitionDtos.AgentDefinitionRequest;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.Problem;
import com.memora.orchestration.agentdef.AgentDefinitionDtos.ValidationReport;
import com.memora.orchestration.executor.AgentExecutor;
import com.memora.orchestration.executor.ExecutorRegistry;
import com.memora.orchestration.expr.MiniSchema;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates an agent definition before it is saved and again before it runs. Instructions are treated as
 * descriptive text only: they are never parsed for permissions, and wording that tries to widen access
 * produces a warning, because the platform enforces tools, capabilities and memory scopes regardless.
 */
@Component
public class AgentDefinitionValidator {

    public static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9.-]{0,59}");
    private static final Set<String> CONTEXT_KEYS = Set.of("query", "maxChars", "includeArtifacts");
    private static final List<String> ESCALATION_WORDS = List.of("ignore previous", "ignore all", "bypass", "disable security",
            "all tenants", "other tenant", "admin access", "root access", "sudo", "rm -rf", "unrestricted");

    private final ToolRegistry tools;
    private final ExecutorRegistry executors;

    public AgentDefinitionValidator(ToolRegistry tools, ExecutorRegistry executors) {
        this.tools = tools;
        this.executors = executors;
    }

    public ValidationReport validate(AgentDefinitionRequest r, String key) {
        List<Problem> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (key == null || !KEY.matcher(key).matches()) {
            errors.add(new Problem("key", "must be 2-64 characters: lowercase letters, digits and hyphens, starting with a letter"));
        }
        if (r.name() == null || r.name().isBlank()) {
            errors.add(new Problem("name", "is required"));
        }
        if (r.role() == null || r.role().isBlank()) {
            errors.add(new Problem("role", "is required (free text, e.g. 'UX designer')"));
        }
        Set<String> capabilities = new HashSet<>();
        for (String c : r.capabilities() == null ? List.<String>of() : r.capabilities()) {
            if (c == null || !CAPABILITY.matcher(c.trim()).matches()) {
                errors.add(new Problem("capabilities", "'" + c + "' must be lowercase letters, digits, dots or hyphens"));
            } else {
                capabilities.add(c.trim());
            }
        }
        Set<String> permitted = new HashSet<>();
        for (String t : r.tools() == null ? List.<String>of() : r.tools()) {
            Optional<Tool> tool = tools.find(t == null ? "" : t.trim());
            if (tool.isEmpty()) {
                errors.add(new Problem("tools", "unknown tool '" + t + "'"));
                continue;
            }
            ToolDescriptor d = tool.get().descriptor();
            permitted.add(d.id());
            if (d.requiredCapability() != null && !capabilities.contains(d.requiredCapability())) {
                errors.add(new Problem("tools", "tool '" + d.id() + "' requires capability '" + d.requiredCapability()
                        + "', which this agent does not declare"));
            }
            if (!d.available()) {
                warnings.add("Tool '" + d.id() + "' is not available in this deployment (" + d.unavailableReason()
                        + "); calls to it will be denied.");
            }
            if (d.requiresApproval()) {
                warnings.add("Tool '" + d.id() + "' requires an approval gate: workflow steps using this agent must set approval.required.");
            }
        }
        if (r.executor() == null || r.executor().isBlank()) {
            errors.add(new Problem("executor", "is required"));
        } else {
            Optional<AgentExecutor> executor = executors.find(r.executor());
            if (executor.isEmpty()) {
                String reason = ExecutorRegistry.MODEL_PLACEHOLDER.id().equals(r.executor())
                        ? ExecutorRegistry.MODEL_PLACEHOLDER.unavailableReason() : "unknown executor";
                errors.add(new Problem("executor", "'" + r.executor() + "' cannot be used: " + reason));
            } else {
                executor.get().validateBehavior(r.behavior(), permitted).forEach(m -> errors.add(new Problem("behavior", m)));
            }
        }
        MiniSchema.checkSchema(r.inputSchema(), "inputSchema").forEach(m -> errors.add(new Problem("inputSchema", m)));
        MiniSchema.checkSchema(r.outputSchema(), "outputSchema").forEach(m -> errors.add(new Problem("outputSchema", m)));
        MemoryPolicy.problems(r.memoryPolicy()).forEach(m -> errors.add(new Problem("memoryPolicy", m)));
        if (r.contextPolicy() != null) {
            for (Map.Entry<String, Object> e : r.contextPolicy().entrySet()) {
                if (!CONTEXT_KEYS.contains(e.getKey())) {
                    errors.add(new Problem("contextPolicy", "unknown setting '" + e.getKey() + "' (allowed: " + CONTEXT_KEYS + ")"));
                }
            }
            if (r.contextPolicy().get("maxChars") instanceof Number n && (n.intValue() < 200 || n.intValue() > 20000)) {
                errors.add(new Problem("contextPolicy", "maxChars must be between 200 and 20000"));
            }
        }
        String instructions = r.instructions() == null ? "" : r.instructions().toLowerCase(Locale.ROOT);
        for (String word : ESCALATION_WORDS) {
            if (instructions.contains(word)) {
                warnings.add("Instructions contain \"" + word + "\". Instructions are descriptive only: they cannot grant tools, "
                        + "capabilities, memory scopes or tenant access, which Memora enforces regardless.");
                break;
            }
        }
        for (ToolDescriptor d : tools.descriptors()) {
            if (!permitted.contains(d.id()) && instructions.contains(d.id())) {
                warnings.add("Instructions mention '" + d.id() + "' but the agent is not permitted to use it.");
            }
        }
        if (permitted.isEmpty() && "template".equals(r.executor())) {
            warnings.add("This agent has no tools, so a template executor cannot do anything.");
        }
        return new ValidationReport(errors.isEmpty(), errors, warnings);
    }
}
