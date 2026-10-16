package com.memora.extraction;

import com.memora.conversation.ConversationService;
import com.memora.conversation.ConversationService.AppendResult;
import com.memora.conversation.MessageRole;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryDtos.MemoryWriteResult;
import com.memora.memory.MemoryScope;
import com.memora.memory.MemoryService;
import com.memora.memory.SourceType;
import com.memora.security.Actor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Extraction hook for new messages: the original message is committed first, then each extracted
 * memory is written in its own short transaction with provenance pointing back to the message.
 * An extraction failure never loses the message.
 */
@Service
public class MessageIngestionService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestionService.class);

    private final ConversationService conversations;
    private final MemoryService memories;
    private final MemoryExtractor extractor;

    public MessageIngestionService(ConversationService conversations, MemoryService memories, MemoryExtractor extractor) {
        this.conversations = conversations;
        this.memories = memories;
        this.extractor = extractor;
    }

    public IngestResult append(Actor actor, UUID conversationId, MessageRole role, String content,
                               Map<String, Object> metadata, boolean extract, String idempotencyKey) {
        AppendResult appended = conversations.append(actor, conversationId, role, content, metadata, idempotencyKey);
        if (!appended.created()) {
            // Idempotent replay: report what the original request extracted, write nothing new.
            return new IngestResult(appended.message(), memories.fromMessage(actor, appended.message().id()).stream()
                    .map(m -> new ExtractedWrite(m, "REPLAYED", null, null)).toList(), true);
        }
        List<ExtractedWrite> writes = new ArrayList<>();
        if (extract) {
            boolean byUser = appended.message().role() == MessageRole.USER && actor.isUser();
            for (ExtractedMemory e : extractor.extract(new ExtractionInput(content, byUser))) {
                try {
                    MemoryWriteResult r = memories.create(actor, toDraft(e, appended), null);
                    writes.add(new ExtractedWrite(r.memory(), r.outcome().name(), r.supersededMemoryId(), e.rule()));
                } catch (RuntimeException ex) {
                    log.warn("Extraction write failed for message {} (rule {}): {}", appended.message().id(), e.rule(),
                            ex.getMessage());
                }
            }
        }
        return new IngestResult(appended.message(), writes, false);
    }

    private MemoryDraft toDraft(ExtractedMemory e, AppendResult appended) {
        Map<String, Object> metadata = new HashMap<>(e.metadata());
        metadata.put("rule", e.rule());
        return MemoryDraft.builder(e.type(), e.content())
                .category(e.category())
                .fact(e.subject(), e.predicate(), e.objectValue())
                .scope(e.userScoped() ? MemoryScope.USER : null)
                .project(appended.conversation().getProjectId())
                .conversation(appended.conversation().getId())
                .tags(e.tags())
                .importance(e.importance())
                .confidence(e.confidence())
                .metadata(metadata)
                .source(SourceType.MESSAGE, appended.message().id(), "message:" + appended.message().seq())
                .extractor(extractor.name())
                .build();
    }

    public record ExtractedWrite(MemoryView memory, String outcome, UUID supersededMemoryId, String rule) {
    }

    public record IngestResult(com.memora.conversation.ConversationDtos.MessageView message,
                               List<ExtractedWrite> extracted, boolean replayed) {
    }

    /** Runs the extractor without storing anything; useful for previews and for documenting the rules. */
    public List<ExtractedMemory> preview(String text, boolean authoredByUser) {
        return extractor.extract(new ExtractionInput(text, authoredByUser));
    }

    public String extractorName() {
        return extractor.name();
    }

}
