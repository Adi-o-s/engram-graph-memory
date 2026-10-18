package com.memora.graph;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every Cypher statement the product runs, read by reflection from the classes that run them. Used to
 * generate docs/GRAPH_QUERIES.md and by {@link GraphQueriesDocTest} to prove the document is a verbatim copy.
 * Regenerate: {@code ./mvnw -q test-compile && java -cp "target/classes:target/test-classes:$(cat cp.txt)" com.memora.graph.GraphQueryCatalog}
 */
public final class GraphQueryCatalog {

    static final List<String> CLASSES = List.of(
            "com.memora.graph.GraphProjection",
            "com.memora.graph.GraphAlerts",
            "com.memora.graph.GraphContextRetriever",
            "com.memora.graph.GraphExplainService",
            "com.memora.graph.GraphInsights");

    private GraphQueryCatalog() {
    }

    /** class simple name -> (constant name -> Cypher). */
    static Map<String, Map<String, String>> queries() throws Exception {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String name : CLASSES) {
            Class<?> c = Class.forName(name);
            Map<String, String> qs = new LinkedHashMap<>();
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == String.class && !Modifier.isPrivate(m)) {
                    f.setAccessible(true);
                    String q = (String) f.get(null);
                    if (q.matches("(?s).*\\b(MATCH|MERGE|CALL|UNWIND)\\b.*")) {
                        qs.put(f.getName(), q.strip());
                    }
                }
            }
            out.put(c.getSimpleName(), qs);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (var e : queries().entrySet()) {
            sb.append("### `").append(e.getKey()).append("`\n\n");
            for (var q : e.getValue().entrySet()) {
                sb.append("#### ").append(q.getKey()).append("\n\n```cypher\n").append(q.getValue()).append("\n```\n\n");
            }
        }
        System.out.print(sb);
    }
}
