package com.memora.orchestration.expr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small subset of JSON Schema, enough to describe agent inputs and outputs and tool
 * arguments without pulling in a schema library: {@code type} (string, number, integer, boolean,
 * object, array, null, or a list of them), {@code required}, {@code properties},
 * {@code additionalProperties: false}, {@code items}, {@code enum}, {@code minItems}, {@code maxItems},
 * {@code minLength}, {@code maxLength}, {@code minimum}, {@code maximum}. Unknown keywords are rejected
 * by {@link #checkSchema} so a typo does not silently disable a check.
 */
public final class MiniSchema {

    private static final Set<String> TYPES = Set.of("string", "number", "integer", "boolean", "object", "array", "null");
    private static final Set<String> KEYWORDS = Set.of("type", "required", "properties", "additionalProperties", "items",
            "enum", "minItems", "maxItems", "minLength", "maxLength", "minimum", "maximum", "description", "title",
            "default", "examples");

    private MiniSchema() {
    }

    /** Validates a value; returns human-readable errors (empty when valid). An empty schema accepts anything. */
    public static List<String> validate(Map<String, Object> schema, Object value) {
        List<String> errors = new ArrayList<>();
        if (schema != null && !schema.isEmpty()) {
            check(schema, value, "$", errors, 0);
        }
        return errors;
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, Object value, String at, List<String> errors, int depth) {
        if (depth > 20 || errors.size() > 50) {
            return;
        }
        Object type = schema.get("type");
        if (type != null) {
            List<String> allowed = type instanceof Collection<?> c ? c.stream().map(String::valueOf).toList() : List.of(String.valueOf(type));
            if (allowed.stream().noneMatch(t -> matches(t, value))) {
                errors.add(at + ": expected " + String.join(" or ", allowed) + " but got " + typeOf(value));
                return;
            }
        }
        if (schema.get("enum") instanceof Collection<?> options && options.stream().noneMatch(o -> String.valueOf(o).equals(String.valueOf(value)))) {
            errors.add(at + ": must be one of " + options);
        }
        if (value instanceof String s) {
            if (schema.get("minLength") instanceof Number n && s.length() < n.intValue()) {
                errors.add(at + ": shorter than " + n + " characters");
            }
            if (schema.get("maxLength") instanceof Number n && s.length() > n.intValue()) {
                errors.add(at + ": longer than " + n + " characters");
            }
        }
        if (value instanceof Number num) {
            if (schema.get("minimum") instanceof Number n && num.doubleValue() < n.doubleValue()) {
                errors.add(at + ": less than " + n);
            }
            if (schema.get("maximum") instanceof Number n && num.doubleValue() > n.doubleValue()) {
                errors.add(at + ": greater than " + n);
            }
        }
        if (value instanceof Collection<?> list) {
            if (schema.get("minItems") instanceof Number n && list.size() < n.intValue()) {
                errors.add(at + ": needs at least " + n + " item(s)");
            }
            if (schema.get("maxItems") instanceof Number n && list.size() > n.intValue()) {
                errors.add(at + ": allows at most " + n + " item(s)");
            }
            if (schema.get("items") instanceof Map<?, ?> items) {
                int i = 0;
                for (Object item : list) {
                    check((Map<String, Object>) items, item, at + "[" + i++ + "]", errors, depth + 1);
                }
            }
        }
        if (value instanceof Map<?, ?> obj) {
            if (schema.get("required") instanceof Collection<?> required) {
                for (Object r : required) {
                    if (obj.get(String.valueOf(r)) == null) {
                        errors.add(at + ": missing required property '" + r + "'");
                    }
                }
            }
            Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            for (Map.Entry<?, ?> e : obj.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (props.get(key) instanceof Map<?, ?> propSchema) {
                    if (e.getValue() != null) {
                        check((Map<String, Object>) propSchema, e.getValue(), at + "." + key, errors, depth + 1);
                    }
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    errors.add(at + ": unexpected property '" + key + "'");
                }
            }
        }
    }

    /** Checks that a schema only uses the supported keywords and types. */
    @SuppressWarnings("unchecked")
    public static List<String> checkSchema(Map<String, Object> schema, String at) {
        List<String> errors = new ArrayList<>();
        if (schema == null) {
            return errors;
        }
        for (String key : schema.keySet()) {
            if (!KEYWORDS.contains(key)) {
                errors.add(at + ": unsupported schema keyword '" + key + "'");
            }
        }
        Object type = schema.get("type");
        if (type != null) {
            List<String> types = type instanceof Collection<?> c ? c.stream().map(String::valueOf).toList() : List.of(String.valueOf(type));
            types.stream().filter(t -> !TYPES.contains(t)).forEach(t -> errors.add(at + ": unknown type '" + t + "'"));
        }
        if (schema.get("properties") instanceof Map<?, ?> props) {
            props.forEach((k, v) -> {
                if (v instanceof Map<?, ?> m) {
                    errors.addAll(checkSchema((Map<String, Object>) m, at + ".properties." + k));
                } else {
                    errors.add(at + ".properties." + k + ": must be an object");
                }
            });
        }
        if (schema.get("items") instanceof Map<?, ?> items) {
            errors.addAll(checkSchema((Map<String, Object>) items, at + ".items"));
        }
        return errors;
    }

    private static boolean matches(String type, Object v) {
        return switch (type) {
            case "string" -> v instanceof String;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Integer || v instanceof Long || (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()));
            case "boolean" -> v instanceof Boolean;
            case "object" -> v instanceof Map;
            case "array" -> v instanceof Collection;
            case "null" -> v == null;
            default -> false;
        };
    }

    private static String typeOf(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map) {
            return "object";
        }
        if (v instanceof Collection) {
            return "array";
        }
        if (v instanceof Number) {
            return "number";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        return "string";
    }
}
