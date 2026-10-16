package com.memora.audit;

import com.memora.common.PageResponse;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Append-only audit trail for the caller's tenant")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public List<AuditService.AuditEventView> recent(Actor actor,
                                                    @RequestParam(required = false) String entityType,
                                                    @RequestParam(required = false) UUID entityId,
                                                    @RequestParam(required = false) Integer limit) {
        return auditService.recent(actor, entityType, entityId, PageResponse.clampSize(limit, 50, 200));
    }
}
