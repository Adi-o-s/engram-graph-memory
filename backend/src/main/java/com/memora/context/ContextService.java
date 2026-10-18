package com.memora.context;

import com.memora.context.ContextDtos.ContextBundle;
import com.memora.context.ContextDtos.ContextPath;
import com.memora.graph.GraphAlerts;
import com.memora.graph.GraphAlerts.Alert;
import com.memora.graph.GraphContextRetriever;
import com.memora.graph.GraphContextRetriever.Candidate;
import com.memora.graph.GraphProjector;
import com.memora.context.ContextDtos.ContextRequest;
import com.memora.context.ContextDtos.ContextStats;
import com.memora.conversation.ConversationDtos.MessageView;
import com.memora.conversation.ConversationService;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.handoff.HandoffService;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryQuery;
import com.memora.memory.MemorySearchService;
import com.memora.memory.MemoryService;
import com.memora.memory.MemoryType;
import com.memora.security.Actor;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles a compact, relevant working context for an agent instead of replaying whole
 * conversations: the task and its latest handoff, current facts, procedures and recent events
 * relevant to the query, and the last few messages, all within a character budget.
 *
 * <p>Memories are chosen by traversal of the tenant's FalkorDB graph ({@link GraphContextRetriever}); each one
 * comes with the path that reached it. PostgreSQL then re-checks every id (RLS and personal scope) before it is
 * used. Agents also get a "changed since you last read it" section from the graph's stale-knowledge alerts.
 * {@code memora.context.retrieval=keyword} restores the PostgreSQL-only path.
 */
@Service
public class ContextService {

    private final MemorySearchService search;
    private final MemoryService memories;
    private final ConversationService conversations;
    private final TaskService tasks;
    private final HandoffService handoffs;
    private final ContextProperties properties;
    private final GraphContextRetriever graph;
    private final GraphProjector projector;
    private final GraphAlerts alerts;
    private final com.memora.project.ProjectService projects;

    public ContextService(MemorySearchService search, MemoryService memories, ConversationService conversations,
                          TaskService tasks, HandoffService handoffs, ContextProperties properties,
                          GraphContextRetriever graph, GraphProjector projector, GraphAlerts alerts,
                          com.memora.project.ProjectService projects) {
        this.projects = projects;
        this.graph = graph;
        this.projector = projector;
        this.alerts = alerts;
        this.search = search;
        this.memories = memories;
        this.conversations = conversations;
        this.tasks = tasks;
        this.handoffs = handoffs;
        this.properties = properties;
    }

    public ContextBundle build(Actor actor, ContextRequest request) {
        int maxMemories = request.maxMemories() != null ? request.maxMemories() : properties.defaultMaxMemories();
        int maxMessages = request.maxMessages() != null ? request.maxMessages() : properties.defaultMaxMessages();
        int maxChars = request.maxChars() != null ? request.maxChars() : properties.defaultMaxChars();

        TaskView task = request.taskId() != null ? tasks.get(actor, request.taskId()) : null;
        HandoffView latestHandoff = null;
        if (task != null) {
            List<HandoffView> taskHandoffs = handoffs.forTask(actor, task.id());
            latestHandoff = taskHandoffs.isEmpty() ? null : taskHandoffs.get(taskHandoffs.size() - 1);
        }
        UUID projectId = request.projectId() != null ? request.projectId() : task != null ? task.projectId() : null;
        if (request.conversationId() != null) {
            var conversation = conversations.get(actor, request.conversationId());
            if (projectId == null) {
                projectId = conversation.projectId();
            }
        }
        String query = request.query() != null && !request.query().isBlank() ? request.query()
                : task != null ? task.title() + " " + (task.description() == null ? "" : task.description()) : null;

        int factBudget = Math.max(1, (int) Math.ceil(maxMemories * 0.5));
        int procedureBudget = Math.max(1, (int) Math.ceil(maxMemories * 0.25));
        int episodeBudget = Math.max(1, maxMemories - factBudget - procedureBudget);

        // A request may pick the retrieval mode (used by the retrieval eval to compare modes on the same data).
        String mode = request.retrieval() != null ? request.retrieval() : properties.retrieval();
        boolean useGraph = !mode.equals("keyword");
        if (useGraph) {
            // Another tenant's project id is a 404, exactly as on the keyword path.
            projects.requireInTenant(actor, projectId);
            // Read-your-writes: apply this tenant's pending outbox rows before traversing.
            projector.catchUp(actor.tenantId());
        }
        Map<UUID, ContextPath> paths = new LinkedHashMap<>();
        UUID taskId = task == null ? null : task.id();
        List<MemoryView> facts = useGraph ? graphRelevant(actor, MemoryType.SEMANTIC, query, projectId, taskId, factBudget, mode, paths)
                : relevant(actor, MemoryType.SEMANTIC, query, projectId, null, factBudget);
        List<MemoryView> procedures = useGraph ? graphRelevant(actor, MemoryType.PROCEDURAL, query, projectId, taskId, procedureBudget, mode, paths)
                : relevant(actor, MemoryType.PROCEDURAL, query, projectId, null, procedureBudget);
        List<MemoryView> episodes = useGraph ? graphRelevant(actor, MemoryType.EPISODIC, query, projectId, taskId, episodeBudget, mode, paths)
                : relevant(actor, MemoryType.EPISODIC, query, projectId, taskId, episodeBudget);
        List<Alert> stale = useGraph ? alerts.openFor(actor) : List.of();
        List<MessageView> recentMessages = request.conversationId() != null && maxMessages > 0
                ? conversations.recent(actor, request.conversationId(), maxMessages) : List.of();

        Budget budget = new Budget(maxChars);
        budget.add("# Working context" + (query != null ? " for: " + abbreviate(query, 120) : ""));
        List<Alert> shownAlerts = new ArrayList<>();
        if (!stale.isEmpty()) {
            budget.add("## \u26a0 Changed since you last read it");
            for (Alert a : stale) {
                String line = "- " + abbreviate(a.oldContent(), 200) + " -> "
                        + (a.newContent() != null ? abbreviate(a.newContent(), 200) : "(" + a.oldStatus().toLowerCase() + ", no replacement)")
                        + (a.handoffId() != null ? " [carried by an open handoff to you]" : " [you read the old version]");
                if (budget.add(line)) {
                    shownAlerts.add(a);
                }
            }
            alerts.markDelivered(actor, shownAlerts.stream().map(Alert::alertId).toList());
        }
        if (task != null) {
            budget.add("## Task");
            budget.add("[" + task.status() + " v" + task.version() + "] " + task.title()
                    + (task.description() != null ? ": " + abbreviate(task.description(), 300) : ""));
            if (!task.state().isEmpty()) {
                budget.add("State: " + abbreviate(task.state().toString(), 400));
            }
            if (latestHandoff != null) {
                budget.add("Latest handoff (" + latestHandoff.status() + "): " + abbreviate(latestHandoff.summary(), 300)
                        + " Next action: " + abbreviate(latestHandoff.nextAction(), 200));
            }
        }
        List<MemoryView> includedFacts = budget.addMemories("## Current facts and decisions", facts);
        List<MemoryView> includedProcedures = budget.addMemories("## Procedures", procedures);
        List<MemoryView> includedEpisodes = budget.addMemories("## Relevant events", episodes);
        List<MessageView> includedMessages = new ArrayList<>();
        if (!recentMessages.isEmpty()) {
            budget.add("## Recent messages");
            for (MessageView m : recentMessages) {
                if (budget.add("- " + m.role() + ": " + abbreviate(m.content(), 400))) {
                    includedMessages.add(m);
                }
            }
        }

        List<UUID> accessed = new ArrayList<>();
        includedFacts.forEach(m -> accessed.add(m.id()));
        includedProcedures.forEach(m -> accessed.add(m.id()));
        includedEpisodes.forEach(m -> accessed.add(m.id()));
        memories.recordAccess(actor, accessed);
        paths.keySet().retainAll(accessed);

        int memoriesTotal = facts.size() + procedures.size() + episodes.size();
        ContextStats stats = new ContextStats(budget.length(), maxChars, accessed.size(), memoriesTotal - accessed.size(),
                includedMessages.size(), recentMessages.size() - includedMessages.size(), budget.truncated);
        Map<UUID, ContextPath> includedPaths = new LinkedHashMap<>();
        accessed.forEach(id -> {
            if (paths.containsKey(id)) {
                includedPaths.put(id, paths.get(id));
            }
        });
        return new ContextBundle(query, task, latestHandoff, includedFacts, includedProcedures, includedEpisodes,
                includedMessages, budget.text(), stats, useGraph ? mode : "keyword", includedPaths, shownAlerts);
    }

    /**
     * Graph traversal first (memories reached from the query's entities, full-text hits, the task and its
     * handoffs, best score first), then the most important visible memories to fill the budget. In hybrid mode
     * PostgreSQL keyword matches are merged in after the graph's own hits. Every id is re-checked in PostgreSQL.
     */
    private List<MemoryView> graphRelevant(Actor actor, MemoryType type, String query, UUID projectId, UUID taskId, int limit,
                                           String mode, Map<UUID, ContextPath> paths) {
        List<Candidate> candidates = graph.retrieve(actor, type, query, projectId, taskId, limit * 2, !mode.equals("hybrid"));
        List<UUID> ids = new ArrayList<>();
        for (Candidate c : candidates) {
            ids.add(c.memoryId());
            paths.putIfAbsent(c.memoryId(), new ContextPath(c.path(), c.hops(), c.score(), c.reached()));
        }
        List<MemoryView> picked = new ArrayList<>(memories.visibleCurrent(actor, ids));
        if (mode.equals("hybrid") && picked.size() < limit) {
            for (MemoryView m : relevant(actor, type, query, projectId, taskId, limit)) {
                if (picked.stream().noneMatch(p -> p.id().equals(m.id()))) {
                    picked.add(m);
                }
            }
        }
        return picked.stream().limit(limit).toList();
    }

    /** Query matches first; if the query finds fewer than the budget, fill with the most important current items. */
    private List<MemoryView> relevant(Actor actor, MemoryType type, String query, UUID projectId, UUID taskId, int limit) {
        Map<UUID, MemoryView> picked = new LinkedHashMap<>();
        if (taskId != null) {
            search.search(actor, MemoryQuery.builder().type(type).task(taskId).limit(limit)
                    .sort(MemoryQuery.Sort.RECENT).build()).items().forEach(m -> picked.putIfAbsent(m.id(), m));
        }
        if (query != null && picked.size() < limit) {
            search.search(actor, MemoryQuery.builder().q(query).type(type).project(projectId).limit(limit).build())
                    .items().forEach(m -> picked.putIfAbsent(m.id(), m));
        }
        if (picked.size() < limit) {
            search.search(actor, MemoryQuery.builder().type(type).project(projectId).limit(limit)
                    .sort(MemoryQuery.Sort.IMPORTANCE).build()).items().forEach(m -> picked.putIfAbsent(m.id(), m));
        }
        return picked.values().stream().limit(limit).toList();
    }

    private static String abbreviate(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 3) + "...";
    }

    private static final class Budget {
        private final int max;
        private final StringBuilder sb = new StringBuilder();
        private boolean truncated;

        Budget(int max) {
            this.max = max;
        }

        boolean add(String line) {
            if (sb.length() + line.length() + 1 > max) {
                truncated = true;
                return false;
            }
            sb.append(line).append('\n');
            return true;
        }

        List<MemoryView> addMemories(String heading, List<MemoryView> items) {
            List<MemoryView> included = new ArrayList<>();
            if (items.isEmpty() || !add(heading)) {
                return included;
            }
            for (MemoryView m : items) {
                String line = "- " + m.content() + " [" + m.type().name().toLowerCase() + ", v" + m.version()
                        + ", id " + m.id().toString().substring(0, 8) + "]";
                if (add(line)) {
                    included.add(m);
                }
            }
            return included;
        }

        int length() {
            return sb.length();
        }

        String text() {
            return sb.toString();
        }
    }
}
