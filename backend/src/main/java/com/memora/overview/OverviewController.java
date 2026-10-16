package com.memora.overview;

import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/overview")
@Tag(name = "Overview", description = "Live dashboard numbers for the caller's tenant")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping
    public OverviewService.Overview overview(Actor actor) {
        return overviewService.overview(actor);
    }
}
