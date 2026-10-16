package com.memora.demo;

import com.memora.identity.AuthDtos.DemoAccountsResponse;
import com.memora.project.ProjectService.ProjectView;
import com.memora.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Demo", description = "Guided multi-agent workflow and local demo accounts")
public class DemoController {

    private final DemoService demoService;
    private final DemoSeeder demoSeeder;

    public DemoController(DemoService demoService, DemoSeeder demoSeeder) {
        this.demoService = demoService;
        this.demoSeeder = demoSeeder;
    }

    @GetMapping("/api/v1/auth/demo-accounts")
    @Operation(summary = "Local demo accounts (empty when demo seeding is disabled)")
    public DemoAccountsResponse demoAccounts() {
        return demoSeeder.describeAccounts();
    }

    @PostMapping("/api/v1/demo/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Step 1: create a sandbox project and teach it facts through conversation messages")
    public DemoService.TeachResult start(Actor actor) {
        return demoService.start(actor);
    }

    @GetMapping("/api/v1/demo/runs")
    public List<ProjectView> runs(Actor actor) {
        return demoService.runs(actor);
    }

    @GetMapping("/api/v1/demo/runs/{projectId}")
    public DemoService.RunStatus status(Actor actor, @PathVariable UUID projectId) {
        return demoService.status(actor, projectId);
    }

    @PostMapping("/api/v1/demo/runs/{projectId}/plan")
    @Operation(summary = "Step 2: Planner creates the task and hands it to Developer")
    public DemoAgents.StepResult plan(Actor actor, @PathVariable UUID projectId) {
        return demoService.plan(actor, projectId);
    }

    @PostMapping("/api/v1/demo/runs/{projectId}/develop")
    @Operation(summary = "Step 3: Developer claims the handoff, builds context, implements, hands to Tester")
    public DemoAgents.StepResult develop(Actor actor, @PathVariable UUID projectId) {
        return demoService.develop(actor, projectId);
    }

    @PostMapping("/api/v1/demo/runs/{projectId}/test")
    @Operation(summary = "Step 4: Tester verifies. With failFirst=true the first attempt fails and the next call retries it.")
    public DemoAgents.StepResult test(Actor actor, @PathVariable UUID projectId,
                                      @RequestParam(defaultValue = "true") boolean failFirst) {
        return demoService.test(actor, projectId, failFirst);
    }
}
