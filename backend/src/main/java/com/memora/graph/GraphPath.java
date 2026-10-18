package com.memora.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * A path through the memory graph, as returned to clients: an ordered list of steps. Each step is a node
 * (label, id, display name) and the relationship type that led to it from the previous step.
 */
public record GraphPath(List<Step> steps, int hops) {

    public record Step(String label, String id, String name, String via) {
    }

    /** Builds a path from the {@code [label, id, name]} triples and relationship types a Cypher query returned. */
    @SuppressWarnings("unchecked")
    static GraphPath of(Object nodes, Object rels) {
        List<Step> steps = new ArrayList<>();
        List<Object> ns = nodes instanceof List<?> l ? (List<Object>) l : List.of();
        List<Object> rs = rels instanceof List<?> l ? (List<Object>) l : List.of();
        for (int i = 0; i < ns.size(); i++) {
            List<Object> n = (List<Object>) ns.get(i);
            String name = n.size() > 2 && n.get(2) != null ? String.valueOf(n.get(2)) : null;
            if (name != null && name.length() > 140) {
                name = name.substring(0, 137) + "...";
            }
            steps.add(new Step(String.valueOf(n.get(0)), n.get(1) == null ? null : String.valueOf(n.get(1)), name,
                    i == 0 || i - 1 >= rs.size() ? null : String.valueOf(rs.get(i - 1))));
        }
        return new GraphPath(steps, rs.size());
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        for (Step s : steps) {
            if (s.via() != null) {
                sb.append(" -").append(s.via()).append("-> ");
            }
            sb.append('(').append(s.label()).append(' ').append(s.name() == null ? s.id() : s.name()).append(')');
        }
        return sb.toString();
    }
}
