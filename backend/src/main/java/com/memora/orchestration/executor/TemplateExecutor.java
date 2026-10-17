package com.memora.orchestration.executor;

import com.memora.orchestration.expr.Conditions;
import com.memora.orchestration.expr.Expressions;
import com.memora.orchestration.tool.ToolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The built-in deterministic executor. An agent's {@code behavior} is a list of tool operations whose
 * arguments are templates over the step view; the step output is a template over the operation results.
 *
 * <pre>
 * {
 *   "operations": [
 *     {"id": "reqs", "tool": "text.extract_requirements", "args": {"text": "${input.goal}"}},
 *     {"id": "doc",  "tool": "document.compose", "args": {...}},
 *     {"tool": "memory.write", "forEach": "${ops.reqs.requirements}", "args": {"content": "${item.text}", ...}},
 *     {"tool": "artifact.write", "when": {"path": "ops.reqs.count", "op": "gt", "value": 0}, "args": {...}}
 *   ],
 *   "output": {"requirements": "${ops.reqs.requirements}"},
 *   "failWhen": {"condition": {...}, "reason": "...", "retryable": false}
 * }
 * </pre>
 *
 * Nothing about an agent's role or name affects execution: two agents with the same behavior do the same
 * thing, and a "Developer" with a documentation behavior writes documentation. Every operation goes
 * through the tool gateway, so behavior cannot do anything the agent's tool permissions do not allow.
 */
@Component
public class TemplateExecutor implements AgentExecutor {

    public static final String ID = "template";
    private static final Pattern OP_ID = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,39}");
    private static final Set<String> BEHAVIOR_KEYS = Set.of("operations", "output", "failWhen", "notes");
    private static final Set<String> OP_KEYS = Set.of("id", "tool", "args", "forEach", "when", "optional", "note");
    private static final int MAX_OPS = 60;
    private static final int MAX_ITEMS = 100;

    @Override
    public ExecutorDescriptor descriptor() {
        return new ExecutorDescriptor(ID, "Deterministic template",
                "Runs the agent's configured tool operations in order. Same inputs and memory give the same output. "
                        + "It does not reason or write free-form text; it composes documents, checks, memory and artifacts "
                        + "from structured data.", true, false, true, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> validateBehavior(Map<String, Object> behavior, Set<String> permittedTools) {
        List<String> errors = new ArrayList<>();
        if (behavior == null || !(behavior.get("operations") instanceof Collection<?> ops) || ops.isEmpty()) {
            errors.add("behavior.operations must be a non-empty list of tool operations");
            return errors;
        }
        behavior.keySet().stream().filter(k -> !BEHAVIOR_KEYS.contains(k))
                .forEach(k -> errors.add("behavior: unknown setting '" + k + "'"));
        if (ops.size() > MAX_OPS) {
            errors.add("behavior.operations allows at most " + MAX_OPS + " operations");
        }
        Set<String> ids = new HashSet<>();
        int i = 0;
        for (Object o : ops) {
            String at = "behavior.operations[" + i++ + "]";
            if (!(o instanceof Map<?, ?> raw)) {
                errors.add(at + " must be an object");
                continue;
            }
            Map<String, Object> op = (Map<String, Object>) raw;
            op.keySet().stream().filter(k -> !OP_KEYS.contains(k)).forEach(k -> errors.add(at + ": unknown setting '" + k + "'"));
            Object tool = op.get("tool");
            if (!(tool instanceof String t) || t.isBlank()) {
                errors.add(at + ".tool is required");
            } else if (!permittedTools.contains(t)) {
                errors.add(at + " uses tool '" + t + "', which is not in this agent's permitted tools");
            }
            if (op.get("id") != null) {
                String id = String.valueOf(op.get("id"));
                if (!OP_ID.matcher(id).matches()) {
                    errors.add(at + ".id must be letters, digits or underscores");
                } else if (!ids.add(id)) {
                    errors.add(at + ".id '" + id + "' is used twice");
                }
            }
            if (op.get("args") != null && !(op.get("args") instanceof Map)) {
                errors.add(at + ".args must be an object");
            }
            Expressions.syntaxErrors(op.get("args")).forEach(e -> errors.add(at + ".args: " + e));
            if (op.get("forEach") != null && !(op.get("forEach") instanceof String)) {
                errors.add(at + ".forEach must be a ${...} placeholder");
            }
            Conditions.validate(op.get("when"), at + ".when", errors, new ArrayList<>());
        }
        Expressions.syntaxErrors(behavior.get("output")).forEach(e -> errors.add("behavior.output: " + e));
        if (behavior.get("failWhen") instanceof Map<?, ?> fw) {
            Conditions.validate(fw.get("condition"), "behavior.failWhen.condition", errors, new ArrayList<>());
        } else if (behavior.get("failWhen") != null) {
            errors.add("behavior.failWhen must be an object with a condition and a reason");
        }
        return errors;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepOutcome execute(ExecutionContext ctx) {
        Map<String, Object> behavior = ctx.agent().behavior();
        Map<String, Object> view = new HashMap<>(ctx.view());
        Map<String, Object> ops = new LinkedHashMap<>();
        view.put("ops", ops);
        int index = 0;
        for (Object raw : (Collection<Object>) behavior.getOrDefault("operations", List.of())) {
            Map<String, Object> op = (Map<String, Object>) raw;
            String id = op.get("id") != null ? String.valueOf(op.get("id")) : "op" + index;
            index++;
            String tool = String.valueOf(op.get("tool"));
            Map<String, Object> when = (Map<String, Object>) op.get("when");
            if (when != null && !Conditions.evaluate(when, view)) {
                ctx.log("skip " + id + " (" + tool + "): condition is false");
                continue;
            }
            boolean optional = Boolean.TRUE.equals(op.get("optional"));
            Map<String, Object> args = op.get("args") == null ? Map.of() : (Map<String, Object>) op.get("args");
            try {
                if (op.get("forEach") != null) {
                    Object items = Expressions.resolve(op.get("forEach"), view);
                    List<Object> list = items instanceof Collection<?> c ? new ArrayList<>(c) : List.of();
                    if (list.size() > MAX_ITEMS) {
                        return StepOutcome.failed(StepOutcome.FailureKind.PERMANENT,
                                "Operation " + id + " would run " + list.size() + " times (limit " + MAX_ITEMS + ")", ctx.log());
                    }
                    List<Object> results = new ArrayList<>();
                    for (int i = 0; i < list.size(); i++) {
                        view.put("item", list.get(i));
                        view.put("index", i);
                        results.add(ctx.tool(tool, Expressions.resolveMap(args, view)));
                    }
                    view.remove("item");
                    view.remove("index");
                    ops.put(id, results);
                    ctx.log(id + ": " + tool + " ×" + results.size());
                } else {
                    Map<String, Object> result = ctx.tool(tool, Expressions.resolveMap(args, view));
                    ops.put(id, result);
                    ctx.log(id + ": " + tool + " → " + String.join(", ", result.keySet()));
                }
            } catch (ToolException e) {
                if (optional && e.kind() != ToolException.Kind.DENIED) {
                    ops.put(id, Map.of("error", e.getMessage()));
                    ctx.log(id + ": " + tool + " failed (optional, continuing): " + e.getMessage());
                    continue;
                }
                ctx.log(id + ": " + tool + " " + e.kind().name().toLowerCase() + ": " + e.getMessage());
                return StepOutcome.failed(e.kind() == ToolException.Kind.RETRYABLE
                        ? StepOutcome.FailureKind.RETRYABLE : StepOutcome.FailureKind.PERMANENT, e.getMessage(), ctx.log());
            }
        }
        Object outputTemplate = behavior.get("output");
        Map<String, Object> output = outputTemplate instanceof Map<?, ?>
                ? Expressions.resolveMap((Map<String, Object>) outputTemplate, view) : new LinkedHashMap<>(ops);
        if (behavior.get("failWhen") instanceof Map<?, ?> fw) {
            Map<String, Object> failView = new HashMap<>(view);
            failView.put("output", output);
            Map<String, Object> condition = (Map<String, Object>) fw.get("condition");
            if (condition != null && !condition.isEmpty() && Conditions.evaluate(condition, failView)) {
                String reason = Expressions.toText(Expressions.resolve(fw.get("reason") == null ? "failWhen matched" : fw.get("reason"), failView));
                boolean retryable = Boolean.TRUE.equals(fw.get("retryable"));
                ctx.log("failWhen matched: " + reason);
                return new StepOutcome(StepOutcome.Status.FAILED, output,
                        retryable ? StepOutcome.FailureKind.RETRYABLE : StepOutcome.FailureKind.PERMANENT, reason, ctx.log());
            }
        }
        return StepOutcome.succeeded(output, ctx.log());
    }
}
