package com.memora.memory;

import com.memora.common.PageResponse;
import com.memora.extraction.ExtractedMemory;
import com.memora.extraction.MessageIngestionService;
import com.memora.memory.MemoryDtos.CreateMemoryRequest;
import com.memora.memory.MemoryDtos.HistoryView;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryDtos.MemoryWriteResult;
import com.memora.memory.MemoryDtos.RetractMemoryRequest;
import com.memora.memory.MemoryDtos.ReviseMemoryRequest;
import com.memora.memory.MemoryDtos.UpdateMemoryRequest;
import com.memora.memory.MemorySearchService.SearchResult;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/memories")
@Tag(name = "Memories", description = "Episodic, semantic and procedural memory: write, search, revise, history")
public class MemoryController {

    private final MemoryService memoryService;
    private final MemorySearchService searchService;
    private final MessageIngestionService ingestion;

    public MemoryController(MemoryService memoryService, MemorySearchService searchService,
                            MessageIngestionService ingestion) {
        this.memoryService = memoryService;
        this.searchService = searchService;
        this.ingestion = ingestion;
    }

    @PostMapping
    @Operation(summary = "Store a structured memory. Facts (subject + predicate + value) supersede older values of the same fact.")
    public ResponseEntity<MemoryWriteResult> create(Actor actor, @Valid @RequestBody CreateMemoryRequest r,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        MemoryDraft draft = MemoryDraft.builder(r.type(), r.content())
                .category(r.category())
                .fact(r.subject(), r.predicate(), r.value())
                .scope(r.scope(), r.scopeRef())
                .project(r.projectId()).conversation(r.conversationId()).task(r.taskId())
                .tags(r.tags()).importance(r.importance()).confidence(r.confidence()).expiresAt(r.expiresAt())
                .metadata(r.metadata())
                .source(actor.isAgent() ? SourceType.AGENT : SourceType.API, null, r.sourceRef())
                .build();
        MemoryWriteResult result = memoryService.create(actor, draft, key);
        return ResponseEntity.status(result.outcome() == MemoryDtos.WriteOutcome.DUPLICATE ? 200 : 201).body(result);
    }

    @GetMapping
    @Operation(summary = "Search memories: full-text + keyword, filtered by scope, type and status, ranked by relevance, importance and recency")
    public SearchResult search(Actor actor,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) List<MemoryType> type,
                               @RequestParam(defaultValue = "false") boolean includeHistory,
                               @RequestParam(required = false) UUID projectId,
                               @RequestParam(defaultValue = "true") boolean includeShared,
                               @RequestParam(required = false) UUID conversationId,
                               @RequestParam(required = false) UUID taskId,
                               @RequestParam(required = false) UUID agentId,
                               @RequestParam(required = false) List<String> tag,
                               @RequestParam(required = false) Integer minImportance,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) MemoryQuery.Sort sort,
                               @RequestParam(defaultValue = "false") boolean matchAll,
                               @RequestParam(required = false) Integer limit,
                               @RequestParam(defaultValue = "0") int offset) {
        return searchService.search(actor, MemoryQuery.builder()
                .q(q).types(type).includeHistory(includeHistory).project(projectId).includeShared(includeShared)
                .conversation(conversationId).task(taskId).agent(agentId).tags(tag).minImportance(minImportance)
                .category(category).sort(sort).matchAll(matchAll)
                .limit(PageResponse.clampSize(limit, 20, 100)).offset(Math.max(0, offset))
                .build());
    }

    @GetMapping("/{id}")
    public MemoryView get(Actor actor, @PathVariable UUID id) {
        return memoryService.get(actor, id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Edit metadata in place (importance, tags, confidence, category, expiry); requires expectedLockVersion")
    public MemoryView update(Actor actor, @PathVariable UUID id, @Valid @RequestBody UpdateMemoryRequest request) {
        return memoryService.update(actor, id, request);
    }

    @PostMapping("/{id}/revise")
    @Operation(summary = "Change content by creating a new version that supersedes this one")
    public MemoryWriteResult revise(Actor actor, @PathVariable UUID id, @Valid @RequestBody ReviseMemoryRequest request) {
        return memoryService.revise(actor, id, request);
    }

    @PostMapping("/{id}/retract")
    public MemoryView retract(Actor actor, @PathVariable UUID id, @Valid @RequestBody(required = false) RetractMemoryRequest request) {
        return memoryService.retract(actor, id, request == null ? null : request.reason(),
                request == null ? null : request.expectedLockVersion());
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "All versions of this memory's fact or revision chain, oldest first")
    public HistoryView history(Actor actor, @PathVariable UUID id) {
        return memoryService.history(actor, id);
    }

    @PostMapping("/extract-preview")
    @Operation(summary = "Show what the rule-based extractor would record for some text, without storing anything")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest request) {
        return new PreviewResponse(ingestion.extractorName(),
                ingestion.preview(request.text(), request.authoredByUser() == null || request.authoredByUser()));
    }

    public record PreviewRequest(@NotBlank @Size(max = 20000) String text, Boolean authoredByUser) {
    }

    public record PreviewResponse(String extractor, List<ExtractedMemory> candidates) {
    }
}
