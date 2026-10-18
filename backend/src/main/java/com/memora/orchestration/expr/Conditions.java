package com.memora.orchestration.expr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structured, side-effect-free conditions used for step routing ({@code when}), loop exit
 * ({@code until}), failure rules and completion criteria.
 *
 * <pre>
 * {"path": "steps.qa.output.passed", "op": "eq", "value": true}
 * {"all": [ ... ]}   {"any": [ ... ]}   {"not": { ... }}
 * </pre>
 * Operators: eq, ne, gt, gte, lt, lte, exists, notExists, empty, notEmpty, truthy, falsy, contains,
 * notContains, in. A {@code value} may itself be a {@code ${...}} placeholder.
 */
public final class Conditions {

    public static final Set<String> OPS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "exists", "notExists", "empty",
            "notEmpty", "truthy", "falsy", "contains", "notContains", "in");
    private static final Set<String> UNARY = Set.of("exists", "notExists", "empty", "notEmpty", "truthy", "falsy");

    private Conditions() {
    }

    /** A null or empty condition is true. */
    public static boolean evaluate(Map<String, Object> condition, Map<String, Object> context) {
        return evaluate(condition, context, 0);
    }

    /** Evaluates and also explains the result, for "why did this step run / get skipped". */
    public static Evaluation explain(Map<String, Object> condition, Map<String, Object> context) {
        List<String> trace = new ArrayList<>();
        boolean result = evaluate(condition, context, 0, trace);
        return new Evaluation(result, trace);
    }

    public record Evaluation(boolean result, List<String> trace) {
    }

    private static boolean evaluate(Map<String, Object> c, Map<String, Object> ctx, int depth) {
        return evaluate(c, ctx, depth, null);
    }

    @SuppressWarnings("unchecked")
    private static boolean evaluate(Map<String, Object> c, Map<String, Object> ctx, int depth, List<String> trace) {
        if (c == null || c.isEmpty()) {
            return true;
        }
        if (depth > 16) {
            throw new IllegalArgumentException("Condition is nested too deeply");
        }
        if (c.containsKey("all")) {
            for (Object o : (Collection<Object>) c.get("all")) {
                if (!evaluate((Map<String, Object>) o, ctx, depth + 1, trace)) {
                    return false;
                }
            }
            return true;
        }
        if (c.containsKey("any")) {
            for (Object o : (Collection<Object>) c.get("any")) {
                if (evaluate((Map<String, Object>) o, ctx, depth + 1, trace)) {
                    return true;
                }
            }
            return false;
        }
        if (c.containsKey("not")) {
            return !evaluate((Map<String, Object>) c.get("not"), ctx, depth + 1, trace);
        }
        String path = String.valueOf(c.get("path"));
        String op = String.valueOf(c.getOrDefault("op", "eq"));
        Object actual = Expressions.lookup(ctx, path);
        Object expected = Expressions.resolve(c.get("value"), ctx);
        boolean result = compare(op, actual, expected);
        if (trace != null) {
            trace.add(path + " " + op + (UNARY.contains(op) ? "" : " " + render(expected)) + " → actual "
                    + render(actual) + " → " + result);
        }
        return result;
    }

    static boolean compare(String op, Object actual, Object expected) {
        return switch (op) {
            case "eq" -> equal(actual, expected);
            case "ne" -> !equal(actual, expected);
            case "gt" -> number(actual) != null && number(expected) != null && number(actual) > number(expected);
            case "gte" -> number(actual) != null && number(expected) != null && number(actual) >= number(expected);
            case "lt" -> number(actual) != null && number(expected) != null && number(actual) < number(expected);
            case "lte" -> number(actual) != null && number(expected) != null && number(actual) <= number(expected);
            case "exists" -> actual != null;
            case "notExists" -> actual == null;
            case "empty" -> isEmpty(actual);
            case "notEmpty" -> !isEmpty(actual);
            case "truthy" -> truthy(actual);
            case "falsy" -> !truthy(actual);
            case "contains" -> contains(actual, expected);
            case "notContains" -> !contains(actual, expected);
            case "in" -> expected instanceof Collection<?> list && list.stream().anyMatch(v -> equal(actual, v));
            default -> throw new IllegalArgumentException("Unknown operator " + op);
        };
    }

    /** Validation: returns problems (unknown operators, malformed nodes) and the paths a condition reads. */
    @SuppressWarnings("unchecked")
    public static void validate(Object node, String where, List<String> errors, List<String> paths) {
        if (node == null) {
            return;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            errors.add(where + ": a condition must be an object");
            return;
        }
        Map<String, Object> c = (Map<String, Object>) raw;
        if (c.isEmpty()) {
            return;
        }
        for (String combinator : List.of("all", "any")) {
            if (c.containsKey(combinator)) {
                if (!(c.get(combinator) instanceof Collection<?> list) || list.isEmpty()) {
                    errors.add(where + ": '" + combinator + "' needs a non-empty list");
                    return;
                }
                int i = 0;
                for (Object o : list) {
                    validate(o, where + "." + combinator + "[" + i++ + "]", errors, paths);
                }
                return;
            }
        }
        if (c.containsKey("not")) {
            validate(c.get("not"), where + ".not", errors, paths);
            return;
        }
        Object path = c.get("path");
        if (!(path instanceof String p) || !Expressions.isValidPath(p)) {
            errors.add(where + ": 'path' must be a dotted path such as steps.review.output.passed");
        } else {
            paths.add(p);
        }
        String op = String.valueOf(c.getOrDefault("op", "eq"));
        if (!OPS.contains(op)) {
            errors.add(where + ": unknown operator '" + op + "' (allowed: " + String.join(", ", OPS.stream().sorted().toList()) + ")");
        } else if (!UNARY.contains(op) && !c.containsKey("value")) {
            errors.add(where + ": operator '" + op + "' needs a 'value'");
        }
        if (c.get("value") != null) {
            paths.addAll(Expressions.paths(c.get("value")));
            Expressions.syntaxErrors(c.get("value")).forEach(e -> errors.add(where + ".value: " + e));
        }
    }

    private static boolean equal(Object a, Object b) {
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return Objects.equals(bool(a), bool(b));
        }
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a, b) || String.valueOf(a).equals(String.valueOf(b));
    }

    private static Boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(s);
        }
        return null;
    }

    private static Double number(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean isEmpty(Object o) {
        return o == null || (o instanceof Collection<?> c && c.isEmpty()) || (o instanceof Map<?, ?> m && m.isEmpty())
                || (o instanceof String s && s.isBlank());
    }

    private static boolean truthy(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.doubleValue() != 0;
        }
        return !isEmpty(o) && !"false".equalsIgnoreCase(String.valueOf(o));
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual instanceof Collection<?> c) {
            return c.stream().anyMatch(v -> equal(v, expected));
        }
        if (actual instanceof Map<?, ?> m) {
            return m.containsKey(String.valueOf(expected));
        }
        return actual != null && expected != null
                && String.valueOf(actual).toLowerCase().contains(String.valueOf(expected).toLowerCase());
    }

    private static String render(Object o) {
        String s = o instanceof String ? "\"" + o + "\"" : String.valueOf(o);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
