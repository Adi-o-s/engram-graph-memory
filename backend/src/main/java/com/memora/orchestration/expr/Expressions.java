package com.memora.orchestration.expr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only "language" in workflow and agent configuration: {@code ${path.to.value}} placeholders.
 *
 * <p>There is no code execution, no method calls and no operators. A path walks maps by key and lists
 * by numeric index; the pseudo-segment {@code size} returns the size of a list, map or string. A string
 * that is exactly one placeholder resolves to the raw value (a list stays a list); a placeholder inside
 * longer text is interpolated as text. Unknown paths resolve to null (empty text when interpolated).
 */
public final class Expressions {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{\\s*([^}]*?)\\s*}");
    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*");
    private static final int MAX_DEPTH = 32;

    private Expressions() {
    }

    /** Resolves placeholders in a string, map or list (recursively). Other values are returned unchanged. */
    public static Object resolve(Object template, Map<String, Object> context) {
        return resolve(template, context, 0);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveMap(Map<String, Object> template, Map<String, Object> context) {
        Object r = resolve(template, context);
        return r instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Object resolve(Object template, Map<String, Object> context, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Template is nested too deeply");
        }
        if (template instanceof String s) {
            return resolveString(s, context);
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolve(v, context, depth + 1)));
            return out;
        }
        if (template instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object v : list) {
                out.add(resolve(v, context, depth + 1));
            }
            return out;
        }
        return template;
    }

    private static Object resolveString(String s, Map<String, Object> context) {
        Matcher m = PLACEHOLDER.matcher(s);
        if (m.matches()) {
            return lookupWithDefault(context, m.group(1));
        }
        if (!s.contains("${")) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        m.reset();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(toText(lookupWithDefault(context, m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** {@code path | fallback}: the fallback text is used when the path is missing, null or blank. */
    private static Object lookupWithDefault(Map<String, Object> context, String expression) {
        int bar = expression.indexOf('|');
        if (bar < 0) {
            return lookup(context, expression);
        }
        Object v = lookup(context, expression.substring(0, bar).trim());
        boolean empty = v == null || (v instanceof String str && str.isBlank()) || (v instanceof Collection<?> c && c.isEmpty());
        return empty ? expression.substring(bar + 1).trim() : v;
    }

    /** Looks up a dotted path. Returns null when any segment is missing. */
    public static Object lookup(Map<String, Object> context, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Object current = context;
        for (String segment : path.trim().split("\\.")) {
            if (current == null) {
                return null;
            }
            if (segment.equals("size")) {
                if (current instanceof Collection<?> c) {
                    current = c.size();
                    continue;
                }
                if (current instanceof String str) {
                    current = str.length();
                    continue;
                }
                if (current instanceof Map<?, ?> mm && !mm.containsKey("size")) {
                    current = mm.size();
                    continue;
                }
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list && segment.chars().allMatch(Character::isDigit)) {
                int i = Integer.parseInt(segment);
                current = i < list.size() ? list.get(i) : null;
            } else {
                return null;
            }
        }
        return current;
    }

    public static String toText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> c) {
            List<String> parts = new ArrayList<>();
            for (Object o : c) {
                parts.add(toText(o));
            }
            return String.join(", ", parts);
        }
        return String.valueOf(value);
    }

    /** Every placeholder path used anywhere in a template (for validation). */
    public static List<String> paths(Object template) {
        List<String> out = new ArrayList<>();
        collect(template, out, 0);
        return out;
    }

    private static void collect(Object template, List<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        if (template instanceof String s) {
            Matcher m = PLACEHOLDER.matcher(s);
            while (m.find()) {
                String expr = m.group(1);
                int bar = expr.indexOf('|');
                out.add((bar < 0 ? expr : expr.substring(0, bar)).trim());
            }
        } else if (template instanceof Map<?, ?> map) {
            map.values().forEach(v -> collect(v, out, depth + 1));
        } else if (template instanceof Collection<?> list) {
            list.forEach(v -> collect(v, out, depth + 1));
        }
    }

    /** True when a placeholder path is syntactically valid. */
    public static boolean isValidPath(String path) {
        return path != null && PATH.matcher(path).matches();
    }

    /** Returns syntax problems in a template: unterminated or malformed placeholders. */
    public static List<String> syntaxErrors(Object template) {
        List<String> errors = new ArrayList<>();
        checkSyntax(template, errors, 0);
        return errors;
    }

    private static void checkSyntax(Object template, List<String> errors, int depth) {
        if (depth > MAX_DEPTH) {
            errors.add("nested too deeply");
            return;
        }
        if (template instanceof String s) {
            int open = s.indexOf("${");
            while (open >= 0) {
                int close = s.indexOf('}', open);
                if (close < 0) {
                    errors.add("unterminated placeholder in \"" + abbreviate(s) + "\"");
                    return;
                }
                String path = s.substring(open + 2, close).trim();
                int bar = path.indexOf('|');
                if (bar >= 0) {
                    path = path.substring(0, bar).trim();
                }
                if (!isValidPath(path)) {
                    errors.add("invalid placeholder ${" + path + "}");
                }
                open = s.indexOf("${", close);
            }
        } else if (template instanceof Map<?, ?> map) {
            map.values().forEach(v -> checkSyntax(v, errors, depth + 1));
        } else if (template instanceof Collection<?> list) {
            list.forEach(v -> checkSyntax(v, errors, depth + 1));
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }
}
