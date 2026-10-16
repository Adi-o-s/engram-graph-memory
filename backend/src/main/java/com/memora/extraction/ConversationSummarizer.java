package com.memora.extraction;

import com.memora.conversation.ConversationDtos.ConversationView;
import com.memora.conversation.ConversationDtos.MessageView;
import com.memora.conversation.ConversationService;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryQuery;
import com.memora.memory.MemorySearchService;
import com.memora.memory.MemoryService;
import com.memora.memory.MemoryType;
import com.memora.memory.SourceType;
import com.memora.security.Actor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Deterministic conversation summary: counts, time span and the memories extracted from it,
 * grouped by kind. No language model is involved, so the summary only restates recorded memories.
 */
@Service
public class ConversationSummarizer {

    private final ConversationService conversations;
    private final MemorySearchService search;
    private final MemoryService memories;

    public ConversationSummarizer(ConversationService conversations, MemorySearchService search, MemoryService memories) {
        this.conversations = conversations;
        this.search = search;
        this.memories = memories;
    }

    public ConversationView summarize(Actor actor, UUID conversationId) {
        ConversationView c = conversations.get(actor, conversationId);
        List<MessageView> recent = conversations.recent(actor, conversationId, 1);
        List<MessageView> first = conversations.messagesAfter(actor, conversationId, 0, 1);
        List<MemoryView> derived = search.search(actor, MemoryQuery.builder().conversation(conversationId).limit(100)
                .sort(MemoryQuery.Sort.IMPORTANCE).build()).items();
        Map<String, List<String>> byKind = derived.stream().collect(Collectors.groupingBy(
                m -> m.type() == MemoryType.SEMANTIC ? (m.category() == null ? "fact" : m.category()) : m.type().name().toLowerCase(),
                java.util.LinkedHashMap::new, Collectors.mapping(MemoryView::content, Collectors.toList())));
        StringBuilder sb = new StringBuilder();
        sb.append(c.messageCount()).append(" message(s)");
        if (!first.isEmpty() && !recent.isEmpty()) {
            sb.append(" from ").append(first.get(0).createdAt()).append(" to ").append(recent.get(0).createdAt());
        }
        sb.append(". ");
        if (byKind.isEmpty()) {
            sb.append("No memories were extracted.");
        }
        byKind.forEach((kind, items) -> sb.append(capitalize(kind)).append(": ")
                .append(String.join(" ", items.stream().limit(5).toList())).append(" "));
        String summary = sb.toString().trim();
        if (summary.length() > 3500) {
            summary = summary.substring(0, 3497) + "...";
        }
        memories.create(actor, MemoryDraft.builder(MemoryType.EPISODIC, "Summary of conversation \"" + c.title() + "\": " + summary)
                .category("summary").conversation(conversationId).project(c.projectId()).tags(List.of("summary"))
                .importance(2).source(SourceType.SYSTEM, null, "conversation:" + conversationId)
                .extractor("deterministic-summary-v1").build(), null);
        return conversations.saveSummary(actor, conversationId, summary);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1) + "s";
    }
}
