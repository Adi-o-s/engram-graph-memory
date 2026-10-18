package com.memora.orchestration.tool.builtin;

import static com.memora.orchestration.tool.builtin.Schemas.anyArray;
import static com.memora.orchestration.tool.builtin.Schemas.array;
import static com.memora.orchestration.tool.builtin.Schemas.enumOf;
import static com.memora.orchestration.tool.builtin.Schemas.integer;
import static com.memora.orchestration.tool.builtin.Schemas.object;
import static com.memora.orchestration.tool.builtin.Schemas.req;
import static com.memora.orchestration.tool.builtin.Schemas.string;

import com.memora.common.Json;
import com.memora.graph.GraphContextRetriever;
import com.memora.graph.GraphContextRetriever.Candidate;
import com.memora.graph.GraphProjector;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryDtos.MemoryWriteResult;
import com.memora.memory.MemoryQuery;
import com.memora.memory.MemoryScope;
import com.memora.memory.MemorySearchService;
import com.memora.memory.MemoryService;
import com.memora.memory.MemoryType;
import com.memora.memory.SourceType;
import com.memora.orchestration.agentdef.MemoryPolicy;
import com.memora.orchestration.run.ArtifactService;
import com.memora.orchestration.run.WorkflowArtifact;
import com.memora.orchestration.tool.Tool;
import com.memora.orchestration.tool.ToolContext;
import com.memora.orchestration.tool.ToolDescriptor;
import com.memora.orchestration.tool.ToolException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Memory access for agents, always confined to the agent's memory policy and the run's scopes. */
public final class MemoryTools {

    private MemoryTools() {
    }

    static List<MemoryScope> readableScopes(ToolContext ctx, Object requested) {
        MemoryPolicy policy = ctx.agent().policy();
        if (!(requested instanceof Collection<?> c) || c.isEmpty()) {
            return policy.read();
        }
        List<MemoryScope> out = new ArrayList<>();
        for (Object o : c) {
            MemoryScope s;
            try {
                s = MemoryScope.valueOf(String.valueOf(o).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw ToolException.permanent("Unknown memory scope " + o);
            }
            if (!policy.read().contains(s)) {
                throw ToolException.denied("Memory scope " + s + " is not readable by agent " + ctx.agent().key()
                        + " (policy allows " + policy.read() + ")");
            }
            out.add(s);
        }
        return out;
    }

    static List<MemoryType> types(ToolContext ctx, Object requested) {
        MemoryPolicy policy = ctx.agent().policy();
        List<MemoryType> out = new ArrayList<>();
        if (requested instanceof Collection<?> c) {
            for (Object o : c) {
                MemoryType t = MemoryType.valueOf(String.valueOf(o).toUpperCase(Locale.ROOT));
                if (policy.types().isEmpty() || policy.types().contains(t)) {
                    out.add(t);
                }
            }
            if (out.isEmpty()) {
                throw ToolException.denied("None of the requested memory types are allowed by the agent's policy");
            }
            return out;
        }
        return policy.types();
    }

    static List<Map<String, Object>> search(MemorySearchService search, ToolContext ctx, String query, List<MemoryScope> scopes,
                                            List<MemoryType> types, int limit, String category, List<String> tags) {
        List<String> keys = scopes.stream().map(s -> ScopeKeys.keyFor(s, ctx)).filter(Objects::nonNull).toList();
        if (keys.isEmpty()) {
            return List.of();
        }
        MemoryQuery q = MemoryQuery.builder().q(query).types(types).scopeKeys(keys).limit(limit).category(category)
                .tags(tags).build();
        return search.search(ctx.actor(), q).items().stream().map(MemoryTools::toMap).toList();
    }

    static Map<String, Object> toMap(MemoryView m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id().toString());
        out.put("type", m.type().name());
        out.put("scope", m.scope().name());
        out.put("content", m.content());
        out.put("category", m.category());
        out.put("subject", m.subject());
        out.put("predicate", m.predicate());
        out.put("value", m.value());
        out.put("version", m.version());
        out.put("status", m.status().name());
        out.put("tags", m.tags());
        out.put("createdAt", String.valueOf(m.createdAt()));
        return out;
    }

    @Component
    public static class MemorySearchTool implements Tool {

        private final MemorySearchService search;
        private final MemoryService memories;

        public MemorySearchTool(MemorySearchService search, MemoryService memories) {
            this.search = search;
            this.memories = memories;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("memory.search", "Search memory",
                    "Full-text search over memories the agent's policy allows (run, project, workflow, agent, tenant, or the "
                            + "starting user's personal scope). Results never include other runs' working memory or other users' "
                            + "personal memories.",
                    "memory", "memory.read", false, false, 5000,
                    object(req(), "query", string("Words to search for; empty lists the most recent", 500),
                            "types", anyArray("SEMANTIC, EPISODIC and/or PROCEDURAL", 3),
                            "scopes", anyArray("Subset of the agent's readable scopes", 6),
                            "category", string("Exact category, e.g. decision", 40),
                            "tags", array("Any of these tags", Map.of("type", "string"), 10),
                            "limit", integer("Maximum results (capped by the policy)", 1, 50)),
                    object(req("items", "count")), List.of("Read-only", "Scope-restricted by the agent's memory policy"),
                    "Denied when a requested scope is outside the policy", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            MemoryPolicy policy = ctx.agent().policy();
            int limit = Math.min(args.get("limit") instanceof Number n ? n.intValue() : policy.maxItems(), policy.maxItems());
            List<Map<String, Object>> items = search(search, ctx, (String) args.get("query"), readableScopes(ctx, args.get("scopes")),
                    types(ctx, args.get("types")), limit, (String) args.get("category"),
                    args.get("tags") instanceof List<?> t ? (List<String>) t : List.of());
            List<String> citations = items.stream().map(m -> "[m:" + String.valueOf(m.get("id")).substring(0, 8) + "] " + m.get("content")).toList();
            // The step USED these memories and the agent READ them: graph edges that power stale-knowledge alerts.
            memories.recordAccess(ctx.actor(), items.stream().map(m -> java.util.UUID.fromString(String.valueOf(m.get("id")))).toList(),
                    ctx.stepRunId());
            return Json.map("items", items, "count", items.size(), "query", args.get("query"), "citations", citations,
                    "ids", items.stream().map(m -> m.get("id")).toList());
        }
    }

    @Component
    public static class MemoryWriteTool implements Tool {

        private final MemoryService memories;

        public MemoryWriteTool(MemoryService memories) {
            this.memories = memories;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("memory.write", "Write memory",
                    "Stores a memory with provenance (agent, run, step) in a scope the agent's policy allows. Facts "
                            + "(subject + predicate + value) supersede the previous value and keep history.",
                    "memory", "memory.write", true, false, 5000,
                    object(req("content"), "content", string("The memory text", 4000),
                            "type", enumOf("Memory type", "SEMANTIC", "EPISODIC", "PROCEDURAL"),
                            "scope", enumOf("Target scope (default: the policy's first write scope)",
                                    "TENANT", "PROJECT", "WORKFLOW", "RUN", "AGENT"),
                            "category", string("e.g. decision, requirement, lesson, event", 40),
                            "subject", string("Fact subject", 200), "predicate", string("Fact property", 200),
                            "value", string("Fact value", 500),
                            "tags", array("Tags", Map.of("type", "string"), 20),
                            "importance", integer("1-5", 1, 5)),
                    object(req("id", "outcome")), List.of("Secrets are refused", "Scope must be writable under the policy"),
                    "Permanent failure for invalid or sensitive content", true, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            MemoryPolicy policy = ctx.agent().policy();
            if (policy.write().isEmpty()) {
                throw ToolException.denied("Agent " + ctx.agent().key() + " may not write memory (empty write policy)");
            }
            MemoryScope scope = args.get("scope") == null ? policy.write().get(0)
                    : MemoryScope.valueOf(String.valueOf(args.get("scope")).toUpperCase(Locale.ROOT));
            if (!policy.write().contains(scope)) {
                throw ToolException.denied("Memory scope " + scope + " is not writable by agent " + ctx.agent().key()
                        + " (policy allows " + policy.write() + ")");
            }
            MemoryType type = args.get("type") == null ? MemoryType.SEMANTIC
                    : MemoryType.valueOf(String.valueOf(args.get("type")).toUpperCase(Locale.ROOT));
            String subject = blankToNull(args.get("subject"));
            MemoryDraft.Builder draft = MemoryDraft.builder(type, String.valueOf(args.get("content")))
                    .category(blankToNull(args.get("category")))
                    .scope(scope, ScopeKeys.refFor(scope, ctx))
                    .project(scope == MemoryScope.PROJECT || scope == MemoryScope.RUN ? ctx.projectId() : null)
                    .tags(args.get("tags") instanceof List<?> t ? (List<String>) t : List.of())
                    .importance(args.get("importance") instanceof Number n ? n.intValue() : null)
                    .metadata(Json.map("workflowRunId", String.valueOf(ctx.runId()), "stepKey", ctx.stepKey(),
                            "iteration", ctx.iteration(), "agent", ctx.agent().key()))
                    .source(SourceType.AGENT, null, "workflow-run:" + ctx.runId() + "/step:" + ctx.stepKey());
            if (subject != null) {
                draft.fact(subject, blankToNull(args.get("predicate")), blankToNull(args.get("value")));
            }
            MemoryWriteResult r = memories.create(ctx.actor(), draft.build(), null);
            return Json.map("id", r.memory().id().toString(), "outcome", r.outcome().name(), "version", r.memory().version(),
                    "scope", scope.name(), "supersededId", r.supersededMemoryId() == null ? null : r.supersededMemoryId().toString());
        }
    }

    @Component
    public static class ContextBuildTool implements Tool {

        private final MemorySearchService search;
        private final ArtifactService artifacts;
        private final GraphContextRetriever graph;
        private final GraphProjector projector;
        private final MemoryService memories;

        public ContextBuildTool(MemorySearchService search, ArtifactService artifacts, GraphContextRetriever graph,
                                GraphProjector projector, MemoryService memories) {
            this.search = search;
            this.artifacts = artifacts;
            this.graph = graph;
            this.projector = projector;
            this.memories = memories;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor("context.build", "Build working context",
                    "Assembles a bounded context for this step: current facts and decisions, procedures and lessons, recent "
                            + "events, and the run's artifacts, within a character budget. Only memory the agent may read is used.",
                    "memory", "memory.read", false, false, 5000,
                    object(req(), "query", string("What the step is about", 500), "maxChars", integer("Budget", 200, 20000)),
                    object(req("rendered")), List.of("Read-only", "Bounded by the agent's memory policy maxChars"),
                    "Returns what fits; reports truncation", true, null);
        }

        @Override
        public Map<String, Object> invoke(ToolContext ctx, Map<String, Object> args) {
            MemoryPolicy policy = ctx.agent().policy();
            int maxChars = Math.min(args.get("maxChars") instanceof Number n ? n.intValue()
                    : contextChars(ctx, policy), policy.maxChars());
            String query = args.get("query") == null ? contextQuery(ctx) : String.valueOf(args.get("query"));
            int per = Math.max(2, policy.maxItems() / 2);
            projector.catchUp(ctx.tenantId());
            List<Map<String, Object>> facts = filtered(ctx, query, MemoryType.SEMANTIC, policy, per);
            List<Map<String, Object>> procedures = filtered(ctx, query, MemoryType.PROCEDURAL, policy, Math.max(1, per / 2));
            List<Map<String, Object>> episodes = filtered(ctx, query, MemoryType.EPISODIC, policy, Math.max(1, per / 2));
            StringBuilder sb = new StringBuilder();
            boolean truncated = false;
            List<String> lines = new ArrayList<>();
            lines.add("# Context for " + ctx.agent().name() + " (step " + ctx.stepKey() + ")");
            addSection(lines, "## Facts and decisions", facts);
            addSection(lines, "## Procedures and lessons", procedures);
            addSection(lines, "## Recent events", episodes);
            boolean includeArtifacts = !Boolean.FALSE.equals(ctx.agent().contextPolicy().get("includeArtifacts"));
            List<String> artifactNames = new ArrayList<>();
            if (includeArtifacts && ctx.runId() != null) {
                for (WorkflowArtifact a : artifacts.latestOfRun(ctx.tenantId(), ctx.runId())) {
                    artifactNames.add(a.getName());
                }
                if (!artifactNames.isEmpty()) {
                    lines.add("## Artifacts in this run");
                    artifactNames.forEach(n -> lines.add("- " + n));
                }
            }
            for (String line : lines) {
                if (sb.length() + line.length() + 1 > maxChars) {
                    truncated = true;
                    break;
                }
                sb.append(line).append('\n');
            }
            List<java.util.UUID> used = new ArrayList<>();
            for (List<Map<String, Object>> section : List.of(facts, procedures, episodes)) {
                section.forEach(m -> used.add(java.util.UUID.fromString(String.valueOf(m.get("id")))));
            }
            memories.recordAccess(ctx.actor(), used, ctx.stepRunId());
            Map<String, Object> paths = new LinkedHashMap<>();
            for (List<Map<String, Object>> section : List.of(facts, procedures, episodes)) {
                section.forEach(m -> {
                    if (m.get("path") != null) {
                        paths.put(String.valueOf(m.get("id")), m.get("path"));
                    }
                });
            }
            return Json.map("rendered", sb.toString(), "facts", facts, "procedures", procedures, "episodes", episodes,
                    "retrieval", "graph", "paths", paths,
                    "artifacts", artifactNames, "chars", sb.length(), "maxChars", maxChars, "truncated", truncated,
                    "memoriesIncluded", facts.size() + procedures.size() + episodes.size());
        }

        private List<Map<String, Object>> filtered(ToolContext ctx, String query, MemoryType type, MemoryPolicy policy, int limit) {
            if (!policy.types().isEmpty() && !policy.types().contains(type)) {
                return List.of();
            }
            List<String> keys = policy.read().stream().map(s -> ScopeKeys.keyFor(s, ctx)).filter(Objects::nonNull).toList();
            if (keys.isEmpty()) {
                return List.of();
            }
            // Graph traversal over the run's readable scopes; PostgreSQL re-checks every id.
            List<Candidate> candidates = graph.retrieve(ctx.actor(), type, query, null, null, keys, limit, true);
            Map<java.util.UUID, Candidate> byId = new LinkedHashMap<>();
            candidates.forEach(c -> byId.put(c.memoryId(), c));
            List<Map<String, Object>> hits = new ArrayList<>();
            for (MemoryView v : memories.visibleCurrent(ctx.actor(), new ArrayList<>(byId.keySet()))) {
                Map<String, Object> m = new LinkedHashMap<>(toMap(v));
                Candidate c = byId.get(v.id());
                m.put("path", c.path().render());
                m.put("hops", c.hops());
                hits.add(m);
            }
            if (hits.size() < limit) {
                List<Map<String, Object>> merged = new ArrayList<>(hits);
                for (Map<String, Object> m : search(search, ctx, null, policy.read(), List.of(type), limit, null, List.of())) {
                    if (merged.size() >= limit) {
                        break;
                    }
                    if (merged.stream().noneMatch(x -> x.get("id").equals(m.get("id")))) {
                        merged.add(m);
                    }
                }
                return merged;
            }
            return hits;
        }

        private static void addSection(List<String> lines, String heading, List<Map<String, Object>> items) {
            if (items.isEmpty()) {
                return;
            }
            lines.add(heading);
            for (Map<String, Object> m : items) {
                lines.add("- " + m.get("content") + " [" + String.valueOf(m.get("scope")).toLowerCase(Locale.ROOT) + "]");
            }
        }

        private static int contextChars(ToolContext ctx, MemoryPolicy policy) {
            return ctx.agent().contextPolicy().get("maxChars") instanceof Number n ? n.intValue() : policy.maxChars();
        }

        private static String contextQuery(ToolContext ctx) {
            Object q = ctx.agent().contextPolicy().get("query");
            return q == null ? ctx.agent().role() : String.valueOf(q);
        }
    }

    private static String blankToNull(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
    }
}
