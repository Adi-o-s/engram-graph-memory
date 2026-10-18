package com.memora.orchestration.tool.builtin;

import static com.memora.orchestration.tool.builtin.Schemas.integer;
import static com.memora.orchestration.tool.builtin.Schemas.object;
import static com.memora.orchestration.tool.builtin.Schemas.req;
import static com.memora.orchestration.tool.builtin.Schemas.string;

import com.memora.graph.GraphExplainService;
import com.memora.graph.GraphInsights;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Graph tools: agents query the tenant's FalkorDB memory graph directly. The tenant comes from the run's
 * {@link ToolContext}, never from arguments, and every call goes through the ToolGateway (capability check,
 * audit, redaction) like any other tool.
 */
public final class GraphTools {

    private GraphTools() {
    }

    /** entityKey, or free text whose best-connected known entity is used (e.g. a goal mentioning billing-service). */
    private static String entity(GraphInsights insights, ToolContext ctx, Map<String, Object> args) {
        Object e = args.get("entityKey");
        if (e != null && !String.valueOf(e).isBlank()) {
            return String.valueOf(e);
        }
        Object text = args.get("text");
        if (text == null || String.valueOf(text).isBlank()) {
            throw ToolException.permanent("entityKey or text is required");
        }
        return insights.resolveEntity(ctx.actor(), String.valueOf(text)).orElse("");
    }

    @Component
    public static class NeighboursTool implements Tool {
        private final GraphInsights insights;

        public NeighboursTool(GraphInsights insights) {
            this.insights = insights;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("graph.neighbors", "Graph neighbours",
                    "Entities and current memories connected to an entity in the memory graph, up to 2 hops.",
                    "graph", "memory.read", false, false, 5000,
                    object(req(), "entityKey", string("Entity, e.g. billing-service or postgres", 200),
                            "text", string("Or: free text; its best-connected known entity is used", 2000),
                            "depth", integer("1 or 2", 1, 2)),
                    object(req("neighbours", "count")), List.of("Read-only", "Tenant graph only", "Hides others' personal memories"),
                    "Empty result for an unknown entity", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            int depth = args.get("depth") instanceof Number n ? n.intValue() : 1;
            return insights.neighbours(ctx.actor(), entity(insights, ctx, args), depth);
        }
    }

    @Component
    public static class WhoKnowsTool implements Tool {
        private final GraphInsights insights;

        public WhoKnowsTool(GraphInsights insights) {
            this.insights = insights;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("graph.who_knows", "Who knows about this",
                    "Agents and users ranked by the current memories about an entity they wrote (x2) or read.",
                    "graph", "memory.read", false, false, 5000,
                    object(req(), "entityKey", string("Entity, e.g. billing-service", 200),
                            "text", string("Or: free text; its best-connected known entity is used", 2000)),
                    object(req("ranking", "count")), List.of("Read-only", "Tenant graph only"), "Empty ranking for an unknown entity",
                    true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            return insights.whoKnows(ctx.actor(), entity(insights, ctx, args));
        }
    }

    @Component
    public static class ImpactTool implements Tool {
        private final GraphInsights insights;

        public ImpactTool(GraphInsights insights) {
            this.insights = insights;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("graph.impact", "Impact analysis",
                    "What depends on an entity: components with a current fact pointing at it, and the memories, tasks, "
                            + "handoffs and workflow runs connected to it.",
                    "graph", "memory.read", false, false, 5000,
                    object(req(), "entityKey", string("Entity, e.g. postgres", 200),
                            "text", string("Or: free text; its best-connected known entity is used", 2000)),
                    object(req("dependents", "memories", "tasks", "runs")), List.of("Read-only", "Tenant graph only"),
                    "Empty lists for an unknown entity", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            return insights.impact(ctx.actor(), entity(insights, ctx, args));
        }
    }

    @Component
    public static class ExplainTool implements Tool {
        private final GraphExplainService explain;

        public ExplainTool(GraphExplainService explain) {
            this.explain = explain;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("graph.explain", "Explain a memory",
                    "Provenance of a memory from the graph: the message and conversation it came from, who wrote and read "
                            + "it, which version it superseded; optionally the shortest path from an entity, task or agent.",
                    "graph", "memory.read", false, false, 5000,
                    object(req("memoryId"), "memoryId", string("Memory id", 36),
                            "from", string("Optional start: entity:<key>, task:<id> or agent:<id>", 240)),
                    object(req("provenance")), List.of("Read-only", "Visibility checked in PostgreSQL first"),
                    "Not found for a memory the agent cannot see", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            UUID id;
            try {
                id = UUID.fromString(String.valueOf(args.get("memoryId")));
            } catch (IllegalArgumentException e) {
                throw ToolException.permanent("memoryId must be a UUID");
            }
            GraphExplainService.Explanation x = explain.explain(ctx.actor(), id, args.get("from") == null ? null : String.valueOf(args.get("from")));
            return com.memora.common.Json.map("memoryId", x.memoryId(), "provenance", x.provenance(),
                    "path", x.path() == null ? null : x.path().render(), "hops", x.path() == null ? null : x.path().hops());
        }
    }
}
