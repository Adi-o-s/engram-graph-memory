package com.memora.orchestration.tool.builtin;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compact builders for the MiniSchema maps tools declare. */
final class Schemas {

    private Schemas() {
    }

    static Map<String, Object> object(List<String> required, Object... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < props.length; i += 2) {
            properties.put((String) props[i], props[i + 1]);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        if (!required.isEmpty()) {
            s.put("required", required);
        }
        s.put("properties", properties);
        return s;
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> string(String description, int maxLength) {
        return Map.of("type", "string", "description", description, "maxLength", maxLength);
    }

    static Map<String, Object> integer(String description, int min, int max) {
        return Map.of("type", "integer", "description", description, "minimum", min, "maximum", max);
    }

    static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    static Map<String, Object> array(String description, Map<String, Object> items, int maxItems) {
        return Map.of("type", "array", "description", description, "items", items, "maxItems", maxItems);
    }

    static Map<String, Object> anyArray(String description, int maxItems) {
        return Map.of("type", "array", "description", description, "maxItems", maxItems);
    }

    static Map<String, Object> anyObject(String description) {
        return Map.of("type", "object", "description", description);
    }

    static Map<String, Object> any(String description) {
        return Map.of("description", description);
    }

    static Map<String, Object> enumOf(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", Arrays.asList(values));
    }

    static List<String> req(String... names) {
        return Arrays.asList(names);
    }
}
