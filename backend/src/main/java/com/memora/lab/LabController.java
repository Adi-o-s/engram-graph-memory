package com.memora.lab;

import com.memora.common.PageResponse;
import com.memora.lab.LabDtos.LabRequest;
import com.memora.lab.LabDtos.LabResult;
import com.memora.security.Actor;
import com.memora.security.Authz;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lab")
@Tag(name = "Concurrency lab", description = "Run a measured concurrent workload and check consistency invariants")
public class LabController {

    private final ConcurrencyLabService lab;

    public LabController(ConcurrencyLabService lab) {
        this.lab = lab;
    }

    @PostMapping("/runs")
    @Operation(summary = "Run a concurrent workload in this backend against PostgreSQL and report measured results")
    public LabResult run(Actor actor, @Valid @RequestBody LabRequest request) {
        Authz.requireUser(actor);
        return lab.run(actor, request);
    }

    @GetMapping("/runs")
    public List<LabResult> recent(Actor actor, @RequestParam(required = false) Integer limit) {
        return lab.recent(actor, PageResponse.clampSize(limit, 10, 50));
    }
}
