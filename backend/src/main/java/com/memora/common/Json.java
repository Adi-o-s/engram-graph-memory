package com.memora.common;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Json {

    private Json() {
    }

    /** Insertion-ordered map builder that tolerates null values (unlike Map.of). */
    public static Map<String, Object> map(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
