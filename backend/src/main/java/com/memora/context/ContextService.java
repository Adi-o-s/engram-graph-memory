package com.memora.context;

import com.memora.context.ContextDtos.ContextBundle;
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
 */
@Service
public class ContextService {

    private final MemorySearchService search;
    private final MemoryService memories;
    private final ConversationService conversations;
    private final TaskService tasks;
    private final HandoffService handoffs;
    private final ContextProperties properties;

    public ContextService(MemorySearchService search, MemoryService memories, ConversationService conversations,
                          TaskService tasks, HandoffService handoffs, ContextProperties properties) {
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

        List<MemoryView> facts = relevant(actor, MemoryType.SEMANTIC, query, projectId, null, factBudget);
        List<MemoryView> procedures = relevant(actor, MemoryType.PROCEDURAL, query, projectId, null, procedureBudget);
        List<MemoryView> episodes = relevant(actor, MemoryType.EPISODIC, query, projectId, task == null ? null : task.id(),
                episodeBudget);
        List<MessageView> recentMessages = request.conversationId() != null && maxMessages > 0
                ? conversations.recent(actor, request.conversationId(), maxMessages) : List.of();

        Budget budget = new Budget(maxChars);
        budget.add("# Working context" + (query != null ? " for: " + abbreviate(query, 120) : ""));
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

        int memoriesTotal = facts.size() + procedures.size() + episodes.size();
        ContextStats stats = new ContextStats(budget.length(), maxChars, accessed.size(), memoriesTotal - accessed.size(),
                includedMessages.size(), recentMessages.size() - includedMessages.size(), budget.truncated);
        return new ContextBundle(query, task, latestHandoff, includedFacts, includedProcedures, includedEpisodes,
                includedMessages, budget.text(), stats);
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
