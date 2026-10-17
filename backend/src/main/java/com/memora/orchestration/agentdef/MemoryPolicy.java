package com.memora.orchestration.agentdef;

import com.memora.memory.MemoryScope;
import com.memora.memory.MemoryType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which memory an agent may read and write. Scopes are resolved against the run (RUN = this run,
 * PROJECT = the run's project, WORKFLOW = all runs of this workflow, AGENT = this agent's own notes,
 * USER = the personal memories of the user who started the run, TENANT = shared tenant knowledge).
 * Nothing outside these scopes is ever returned to the agent, whatever its instructions say.
 */
public record MemoryPolicy(List<MemoryScope> read, List<MemoryScope> write, List<MemoryType> types, int maxItems,
                           int maxChars) {

    public static final List<MemoryScope> DEFAULT_READ = List.of(MemoryScope.RUN, MemoryScope.PROJECT, MemoryScope.TENANT);
    public static final List<MemoryScope> DEFAULT_WRITE = List.of(MemoryScope.RUN, MemoryScope.PROJECT);
    private static final List<String> KEYS = List.of("read", "write", "types", "maxItems", "maxChars");

    public static MemoryPolicy of(Map<String, Object> raw) {
        Map<String, Object> m = raw == null ? Map.of() : raw;
        List<MemoryScope> read = scopes(m.get("read"), DEFAULT_READ);
        List<MemoryScope> write = scopes(m.get("write"), DEFAULT_WRITE);
        List<MemoryType> types = new ArrayList<>();
        if (m.get("types") instanceof Collection<?> c) {
            for (Object o : c) {
                types.add(MemoryType.valueOf(String.valueOf(o).toUpperCase(Locale.ROOT)));
            }
        }
        int maxItems = m.get("maxItems") instanceof Number n ? n.intValue() : 10;
        int maxChars = m.get("maxChars") instanceof Number n ? n.intValue() : 3000;
        return new MemoryPolicy(read, write, types, maxItems, maxChars);
    }

    private static List<MemoryScope> scopes(Object value, List<MemoryScope> fallback) {
        if (!(value instanceof Collection<?> c)) {
            return fallback;
        }
        List<MemoryScope> out = new ArrayList<>();
        for (Object o : c) {
            out.add(MemoryScope.valueOf(String.valueOf(o).toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    /** Validation messages for a raw policy (unknown scopes, impossible writes, out-of-range limits). */
    public static List<String> problems(Map<String, Object> raw) {
        List<String> errors = new ArrayList<>();
        if (raw == null) {
            return errors;
        }
        for (String field : List.of("read", "write")) {
            Object v = raw.get(field);
            if (v == null) {
                continue;
            }
            if (!(v instanceof Collection<?> c)) {
                errors.add("memoryPolicy." + field + " must be a list of scopes");
                continue;
            }
            for (Object o : c) {
                try {
                    MemoryScope s = MemoryScope.valueOf(String.valueOf(o).toUpperCase(Locale.ROOT));
                    if (field.equals("write") && s == MemoryScope.USER) {
                        errors.add("memoryPolicy.write cannot include USER: agents never write a person's private memories");
                    }
                } catch (IllegalArgumentException e) {
                    errors.add("memoryPolicy." + field + ": unknown scope '" + o
                            + "' (allowed: TENANT, PROJECT, USER, WORKFLOW, RUN, AGENT)");
                }
            }
        }
        if (raw.get("types") instanceof Collection<?> c) {
            for (Object o : c) {
                try {
                    MemoryType.valueOf(String.valueOf(o).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    errors.add("memoryPolicy.types: unknown memory type '" + o + "'");
                }
            }
        }
        if (raw.get("maxItems") instanceof Number n && (n.intValue() < 1 || n.intValue() > 50)) {
            errors.add("memoryPolicy.maxItems must be between 1 and 50");
        }
        if (raw.get("maxChars") instanceof Number n && (n.intValue() < 200 || n.intValue() > 20000)) {
            errors.add("memoryPolicy.maxChars must be between 200 and 20000");
        }
        for (String key : raw.keySet()) {
            if (!KEYS.contains(key)) {
                errors.add("memoryPolicy: unknown setting '" + key + "'");
            }
        }
        return errors;
    }
}
