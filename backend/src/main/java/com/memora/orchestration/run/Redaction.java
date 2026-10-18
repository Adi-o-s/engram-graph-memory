package com.memora.orchestration.run;

import com.memora.extraction.SensitiveContentGuard;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies a JSON-like value, replacing any string that looks like a credential, before it is persisted. */
final class Redaction {

    static final String MARK = "[redacted: looks like a secret]";

    private Redaction() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Map<String, Object> value) {
        return value == null ? null : (Map<String, Object>) deep(value, 0);
    }

    private static Object deep(Object v, int depth) {
        if (depth > 32) {
            return v;
        }
        if (v instanceof String s) {
            return SensitiveContentGuard.looksSensitive(s) ? MARK : s;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), deep(x, depth + 1)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            c.forEach(x -> out.add(deep(x, depth + 1)));
            return out;
        }
        return v;
    }
}
