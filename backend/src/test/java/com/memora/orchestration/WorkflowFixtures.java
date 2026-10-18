package com.memora.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.memora.support.ApiClient;
import com.memora.support.ApiClient.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small, user-defined agents and workflows for engine tests. None of them is a built-in role. */
final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Writes {label}.md and echoes its inputs. */
    static Map<String, Object> echoAgent(String key) {
        Map<String, Object> body = base(key, "Echo " + key, "Echo");
        body.put("capabilities", List.of("document", "artifact.write"));
        body.put("tools", List.of("document.compose", "artifact.write"));
        body.put("behavior", Map.of(
                "operations", List.of(
                        Map.of("id", "doc", "tool", "document.compose", "args", Map.of("title", "${input.label}",
                                "sections", List.of(Map.of("heading", "Value", "body", "${input.value | none}")))),
                        Map.of("id", "save", "tool", "artifact.write", "args", Map.of("name", "${input.label}.md", "kind", "markdown",
                                "content", "${ops.doc.markdown}"))),
                "output", Map.of("label", "${input.label}", "value", "${input.value}", "flag", "${input.flag}", "passed", "${input.passed}")));
        return body;
    }

    /** Fails permanently when its input value is "bad" (a rule, not a transient error). */
    static Map<String, Object> strictAgent(String key) {
        Map<String, Object> body = echoAgent(key);
        body.put("name", "Strict " + key);
        @SuppressWarnings("unchecked")
        Map<String, Object> behavior = new HashMap<>((Map<String, Object>) body.get("behavior"));
        behavior.put("failWhen", Map.of("condition", Map.of("path", "input.value", "op", "eq", "value", "bad"),
                "reason", "Value ${input.value} violates the rule"));
        body.put("behavior", behavior);
        return body;
    }

    static Map<String, Object> base(String key, String name, String role) {
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("name", name);
        body.put("role", role);
        body.put("executor", "template");
        body.put("maxAttempts", 3);
        body.put("backoffSeconds", 0);
        return body;
    }

    static String createAgent(ApiClient admin, Map<String, Object> body) {
        Response r = admin.post("/api/v1/agent-definitions", body);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.text("key");
    }

    static Map<String, Object> step(String key, String agent, List<String> dependsOn, Map<String, Object> inputs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("key", key);
        s.put("name", "Step " + key);
        s.put("agent", agent);
        s.put("dependsOn", dependsOn);
        s.put("inputs", inputs);
        return s;
    }

    static Map<String, Object> with(Map<String, Object> step, Object... kv) {
        Map<String, Object> copy = new LinkedHashMap<>(step);
        for (int i = 0; i < kv.length; i += 2) {
            copy.put((String) kv[i], kv[i + 1]);
        }
        return copy;
    }

    @SafeVarargs
    static Map<String, Object> spec(Map<String, Object>... steps) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("inputs", List.of(Map.of("name", "mode", "type", "string")));
        spec.put("steps", new ArrayList<>(Arrays.asList(steps)));
        return spec;
    }

    static String createWorkflow(ApiClient admin, String key, Map<String, Object> spec) {
        Response r = admin.post("/api/v1/workflows", Map.of("key", key, "name", "Workflow " + key, "spec", spec));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        return r.text("id");
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], kv[i + 1]);
        }
        return out;
    }
}
