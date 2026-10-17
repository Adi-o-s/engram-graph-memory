package com.memora.orchestration.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.extraction.SensitiveContentGuard;
import com.memora.orchestration.expr.MiniSchema;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The single door between an executor and the platform. Every call is checked, in this order:
 * <ol>
 *   <li>the tool exists and is available in this deployment;</li>
 *   <li>the tool is in the agent definition's {@code tools} list (as pinned when the run started);</li>
 *   <li>the agent declares the tool's required capability;</li>
 *   <li>a tool that requires approval only runs in a step that passed an approval gate;</li>
 *   <li>the arguments match the tool's input schema.</li>
 * </ol>
 * Denied and failed calls are recorded like successful ones, in {@code tool_invocations}; calls with side
 * effects are also written to the audit trail. Recorded inputs and outputs are summaries, with anything that
 * looks like a secret redacted.
 */
@Component
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    private final ToolRegistry registry;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public ToolGateway(ToolRegistry registry, JdbcTemplate jdbc, ObjectMapper objectMapper, AuditService audit) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public Map<String, Object> invoke(ToolContext ctx, String toolId, Map<String, Object> args) {
        long start = System.nanoTime();
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        Tool tool = registry.find(toolId).orElse(null);
        boolean sideEffects = tool != null && tool.descriptor().sideEffects();
        try {
            authorize(ctx, toolId, tool);
            List<String> problems = MiniSchema.validate(tool.descriptor().inputSchema(), safeArgs);
            if (!problems.isEmpty()) {
                throw ToolException.permanent("Invalid arguments for " + toolId + ": " + String.join("; ", problems));
            }
            Map<String, Object> result = tool.invoke(ctx, safeArgs);
            Map<String, Object> out = result == null ? Map.of() : result;
            record(ctx, toolId, "SUCCEEDED", sideEffects, safeArgs, out, null, start);
            if (sideEffects) {
                audit.record(ctx.actor(), "tool." + toolId, "workflow_step", ctx.stepRunId(),
                        Json.map("runId", ctx.runId(), "agent", ctx.agent().key()));
            }
            return out;
        } catch (ToolException e) {
            record(ctx, toolId, e.kind() == ToolException.Kind.DENIED ? "DENIED" : "FAILED", sideEffects, safeArgs,
                    Map.of(), e.getMessage(), start);
            throw e;
        } catch (ApiException e) {
            // A 4xx from a platform service (not found, invalid, forbidden) will not change on retry.
            record(ctx, toolId, "FAILED", sideEffects, safeArgs, Map.of(), e.getMessage(), start);
            if (e.status().value() == 403) {
                throw ToolException.denied(toolId + ": " + e.getMessage());
            }
            throw e.status().is4xxClientError() ? ToolException.permanent(toolId + ": " + e.getMessage())
                    : ToolException.retryable(toolId + ": " + e.getMessage());
        } catch (RuntimeException e) {
            record(ctx, toolId, "FAILED", sideEffects, safeArgs, Map.of(), e.getMessage(), start);
            throw ToolException.retryable(toolId + " failed: " + e.getMessage());
        }
    }

    /** Authorization only (no call); also used by validation to explain why a configuration is rejected. */
    public void authorize(ToolContext ctx, String toolId, Tool tool) {
        if (tool == null) {
            throw ToolException.denied("Unknown tool " + toolId);
        }
        ToolDescriptor d = tool.descriptor();
        if (!d.available()) {
            throw ToolException.denied("Tool " + toolId + " is not available here: " + d.unavailableReason());
        }
        if (!ctx.agent().tools().contains(toolId)) {
            throw ToolException.denied("Agent " + ctx.agent().key() + " is not permitted to use " + toolId
                    + " (permitted: " + String.join(", ", ctx.agent().tools()) + ")");
        }
        if (d.requiredCapability() != null && !ctx.agent().capabilities().contains(d.requiredCapability())) {
            throw ToolException.denied("Agent " + ctx.agent().key() + " lacks capability '" + d.requiredCapability()
                    + "' required by " + toolId);
        }
        if (d.requiresApproval() && !ctx.approved()) {
            throw ToolException.denied(toolId + " requires a human approval gate on the step");
        }
    }

    private void record(ToolContext ctx, String toolId, String status, boolean sideEffects, Map<String, Object> args,
                        Map<String, Object> result, String error, long startNanos) {
        int durationMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000);
        try {
            jdbc.update("""
                    INSERT INTO tool_invocations (id, tenant_id, run_id, step_run_id, agent_definition_id, tool_id, status,
                                                  side_effects, input_summary, output_summary, error, duration_ms, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                    """, UUID.randomUUID(), ctx.tenantId(), ctx.runId(), ctx.stepRunId(), ctx.agent().definitionId(),
                    toolId.length() > 60 ? toolId.substring(0, 60) : toolId, status, sideEffects,
                    json(summarize(args)), json(summarize(result)), error == null ? null : redact(error, 1000),
                    durationMs, Timestamp.from(Instant.now()));
        } catch (RuntimeException e) {
            log.warn("Could not record tool invocation {}: {}", toolId, e.getMessage());
        }
    }

    /** Keys and short, redacted values; lists and objects are reduced to their size. */
    static Map<String, Object> summarize(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (out.size() >= 20) {
                return;
            }
            if (v instanceof String s) {
                out.put(k, redact(s, 160));
            } else if (v instanceof Collection<?> c) {
                out.put(k, "list(" + c.size() + ")");
            } else if (v instanceof Map<?, ?> m) {
                out.put(k, "object(" + m.size() + " keys)");
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    static String redact(String s, int max) {
        if (SensitiveContentGuard.looksSensitive(s)) {
            return "[redacted: looks like a secret]";
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
