package com.memora.context;

import com.memora.context.ContextDtos.ContextBundle;
import com.memora.context.ContextDtos.ContextRequest;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/context")
@Tag(name = "Context", description = "Bounded working context for agents")
public class ContextController {

    private final ContextService contextService;

    public ContextController(ContextService contextService) {
        this.contextService = contextService;
    }

    @PostMapping
    @Operation(summary = "Assemble relevant memories, task state and recent messages within a size budget")
    public ContextBundle build(Actor actor, @Valid @RequestBody ContextRequest request) {
        return contextService.build(actor, request);
    }
}
