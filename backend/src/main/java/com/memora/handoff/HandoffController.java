package com.memora.handoff;

import com.memora.common.PageResponse;
import com.memora.handoff.HandoffDtos.CompleteHandoffRequest;
import com.memora.handoff.HandoffDtos.CreateHandoffRequest;
import com.memora.handoff.HandoffDtos.FailHandoffRequest;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.handoff.HandoffDtos.RejectHandoffRequest;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/handoffs")
@Tag(name = "Handoffs", description = "Structured handoffs between agents, with acknowledgement, leases and retries")
public class HandoffController {

    private final HandoffService handoffService;

    public HandoffController(HandoffService handoffService) {
        this.handoffService = handoffService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Hand a task to another agent (send Idempotency-Key to make retries safe)")
    public HandoffView create(Actor actor, @Valid @RequestBody CreateHandoffRequest request,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return handoffService.create(actor, request, idempotencyKey);
    }

    @GetMapping
    public List<HandoffView> list(Actor actor, @RequestParam(required = false) UUID taskId,
                                  @RequestParam(required = false) HandoffStatus status,
                                  @RequestParam(required = false) Integer limit) {
        if (taskId != null) {
            return handoffService.forTask(actor, taskId);
        }
        return handoffService.recent(actor, status, PageResponse.clampSize(limit, 50, 200));
    }

    @GetMapping("/{id}")
    public HandoffView get(Actor actor, @PathVariable UUID id) {
        return handoffService.get(actor, id);
    }

    @PostMapping("/{id}/ack")
    @Operation(summary = "Claim a pending handoff under a lease (idempotent for the claiming agent)")
    public HandoffView acknowledge(Actor actor, @PathVariable UUID id) {
        return handoffService.acknowledge(actor, id);
    }

    @PostMapping("/{id}/complete")
    public HandoffView complete(Actor actor, @PathVariable UUID id, @RequestBody(required = false) CompleteHandoffRequest request) {
        return handoffService.complete(actor, id, request == null ? null : request.result());
    }

    @PostMapping("/{id}/fail")
    public HandoffView fail(Actor actor, @PathVariable UUID id, @Valid @RequestBody FailHandoffRequest request) {
        return handoffService.fail(actor, id, request.reason(), request.result());
    }

    @PostMapping("/{id}/reject")
    public HandoffView reject(Actor actor, @PathVariable UUID id, @Valid @RequestBody RejectHandoffRequest request) {
        return handoffService.reject(actor, id, request.reason());
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Return a FAILED handoff to the receiver's inbox")
    public HandoffView retry(Actor actor, @PathVariable UUID id) {
        return handoffService.retry(actor, id);
    }
}
