package com.memora.conversation;

import com.memora.common.PageResponse;
import com.memora.conversation.ConversationDtos.ConversationView;
import com.memora.conversation.ConversationDtos.CreateConversationRequest;
import com.memora.conversation.ConversationDtos.CreateMessageRequest;
import com.memora.conversation.ConversationDtos.MessageView;
import com.memora.extraction.ConversationSummarizer;
import com.memora.extraction.MessageIngestionService;
import com.memora.extraction.MessageIngestionService.IngestResult;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryService;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Conversations", description = "Persistent conversations and messages with memory extraction")
public class ConversationController {

    private final ConversationService conversationService;
    private final MessageIngestionService ingestion;
    private final ConversationSummarizer summarizer;
    private final MemoryService memoryService;

    public ConversationController(ConversationService conversationService, MessageIngestionService ingestion,
                                  ConversationSummarizer summarizer, MemoryService memoryService) {
        this.conversationService = conversationService;
        this.ingestion = ingestion;
        this.summarizer = summarizer;
        this.memoryService = memoryService;
    }

    @PostMapping
    public ResponseEntity<ConversationView> create(Actor actor, @Valid @RequestBody CreateConversationRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return ResponseEntity.status(201).body(
                conversationService.create(actor, request.projectId(), request.title(), request.sessionId(), key));
    }

    @GetMapping
    @Operation(summary = "List conversations, newest activity first (members see their own; admins and agents see all)")
    public PageResponse<ConversationView> list(Actor actor, @RequestParam(required = false) UUID projectId,
                                               @RequestParam(required = false) Boolean mine,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(required = false) Integer size) {
        return conversationService.list(actor, projectId, mine, Math.max(0, page), PageResponse.clampSize(size, 20, 100));
    }

    @GetMapping("/{id}")
    public ConversationView get(Actor actor, @PathVariable UUID id) {
        return conversationService.get(actor, id);
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Append a message; by default the rule-based extractor records memories from it")
    public ResponseEntity<IngestResult> addMessage(Actor actor, @PathVariable UUID id,
                                                   @Valid @RequestBody CreateMessageRequest request,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        IngestResult result = ingestion.append(actor, id, request.role(), request.content(), request.metadata(),
                request.extract() == null || request.extract(), key);
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(result);
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "Messages in chronological order: page/size, or afterSeq/limit for incremental reads")
    public PageResponse<MessageView> messages(Actor actor, @PathVariable UUID id,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(required = false) Integer size,
                                              @RequestParam(required = false) Long afterSeq) {
        int pageSize = PageResponse.clampSize(size, 50, 200);
        if (afterSeq != null) {
            List<MessageView> items = conversationService.messagesAfter(actor, id, afterSeq, pageSize);
            return new PageResponse<>(items, 0, pageSize, items.size(), 1);
        }
        return conversationService.messages(actor, id, Math.max(0, page), pageSize);
    }

    @GetMapping("/{id}/messages/{messageId}/memories")
    @Operation(summary = "Memories extracted from one message (provenance)")
    public List<MemoryView> messageMemories(Actor actor, @PathVariable UUID id, @PathVariable UUID messageId) {
        MessageView message = conversationService.message(actor, messageId);
        if (!message.conversationId().equals(id)) {
            throw com.memora.common.ApiException.notFound("Message", messageId);
        }
        return memoryService.fromMessage(actor, messageId);
    }

    @PostMapping("/{id}/summarize")
    @Operation(summary = "Build and store a deterministic summary from the conversation's extracted memories")
    public ConversationView summarize(Actor actor, @PathVariable UUID id) {
        return summarizer.summarize(actor, id);
    }
}
