package com.memora.demo;

import com.memora.agent.AgentRole;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.context.ContextDtos.ContextBundle;
import com.memora.context.ContextDtos.ContextRequest;
import com.memora.context.ContextService;
import com.memora.conversation.MessageRole;
import com.memora.extraction.MessageIngestionService;
import com.memora.handoff.HandoffDtos.CreateHandoffRequest;
import com.memora.handoff.HandoffDtos.HandoffView;
import com.memora.handoff.HandoffService;
import com.memora.handoff.HandoffStatus;
import com.memora.memory.MemoryDtos.MemoryDraft;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryDtos.MemoryWriteResult;
import com.memora.memory.MemoryQuery;
import com.memora.memory.MemorySearchService;
import com.memora.memory.MemoryService;
import com.memora.memory.MemoryType;
import com.memora.memory.SourceType;
import com.memora.security.Actor;
import com.memora.task.TaskDtos.CreateTaskRequest;
import com.memora.task.TaskDtos.TaskView;
import com.memora.task.TaskDtos.UpdateTaskRequest;
import com.memora.task.TaskService;
import com.memora.task.TaskStatus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Three deterministic demo agents. They hold no state between steps: each step starts from the
 * agent's persisted inbox and shared memory, which is what lets a different process (or a restarted
 * backend) continue the work. All writes go through the same services the REST API uses.
 */
@Component
public class DemoAgents {

    static final String TASK_TITLE = "Add a /health endpoint that reports database status";
    static final List<String> REQUIREMENTS = List.of(
            "The /health endpoint must report database connectivity.",
            "The /health endpoint must respond within 200 ms.",
            "The /health endpoint must not expose credentials or connection strings.");

    private final TaskService tasks;
    private final HandoffService handoffs;
    private final MemoryService memories;
    private final MemorySearchService search;
    private final ContextService context;
    private final MessageIngestionService ingestion;

    public DemoAgents(TaskService tasks, HandoffService handoffs, MemoryService memories, MemorySearchService search,
                      ContextService context, MessageIngestionService ingestion) {
        this.tasks = tasks;
        this.handoffs = handoffs;
        this.memories = memories;
        this.search = search;
        this.context = context;
        this.ingestion = ingestion;
    }

    static String describe(AgentRole role) {
        return switch (role) {
            case PLANNER -> "Turns requests into tasks with requirements and hands them to a developer";
            case DEVELOPER -> "Retrieves context, implements the task and records findings";
            case TESTER -> "Verifies the implementation against requirements and shared facts";
            case CUSTOM -> "Custom agent";
        };
    }

    static List<String> capabilities(AgentRole role) {
        return switch (role) {
            case PLANNER -> List.of("plan", "write-requirements", "create-tasks", "handoff");
            case DEVELOPER -> List.of("read-context", "implement", "record-findings", "handoff");
            case TESTER -> List.of("verify", "record-results", "retry");
            case CUSTOM -> List.of();
        };
    }

    public record StepResult(String step, String agent, String outcome, UUID taskId, UUID handoffId,
                             List<String> log, Map<String, Object> details) {
    }

    // ---------------------------------------------------------------- Planner

    public StepResult plan(Actor planner, UUID projectId, UUID developerId, UUID conversationId) {
        List<String> log = new ArrayList<>();
        ContextBundle ctx = context.build(planner, new ContextRequest("database language framework backend", projectId,
                null, null, 8, 0, 2000));
        log.add("Read " + ctx.facts().size() + " current fact(s) from shared memory");

        TaskView task = tasks.create(planner, new CreateTaskRequest(projectId, TASK_TITLE,
                "Expose GET /health so operators can see whether the service and its database are up.", 2, null,
                Json.map("requirements", REQUIREMENTS, "source", "guided-demo")), "demo-plan-" + projectId);
        log.add("Created task \"" + task.title() + "\" (version " + task.version() + ")");

        List<UUID> refs = new ArrayList<>();
        for (int i = 0; i < REQUIREMENTS.size(); i++) {
            MemoryWriteResult r = memories.create(planner, MemoryDraft.builder(MemoryType.SEMANTIC, "Requirement: " + REQUIREMENTS.get(i))
                    .category("requirement").project(projectId).task(task.id()).tags(List.of("requirement", "health"))
                    .importance(4).source(SourceType.AGENT, null, "planner").build(), "demo-req-" + task.id() + "-" + i);
            refs.add(r.memory().id());
        }
        log.add("Recorded " + REQUIREMENTS.size() + " requirement memories linked to the task");
        Optional<MemoryView> db = currentFact(planner, projectId, "uses.database");
        db.ifPresent(f -> refs.add(f.id()));
        MemoryWriteResult decision = memories.create(planner, MemoryDraft.builder(MemoryType.SEMANTIC,
                        "Decision: the health check runs against the project's current database as recorded in shared memory.")
                .category("decision").project(projectId).task(task.id()).tags(List.of("decision", "health")).importance(4)
                .source(SourceType.AGENT, null, "planner").build(), "demo-decision-" + task.id());
        refs.add(decision.memory().id());

        HandoffView handoff = handoffs.create(planner, new CreateHandoffRequest(task.id(), null, developerId,
                "Implement GET /health. Requirements and the current database fact are attached as memory references.",
                refs, List.of("Use the database recorded as current in shared memory", "No credentials in the response"),
                Map.of("plan", List.of("Add controller", "Add database connectivity check", "Hand off to Tester")),
                "Acknowledge, build context for the task, implement, then hand off to Tester.", task.version(), 3),
                "demo-handoff-plan-" + task.id());
        log.add("Handed off to Developer with " + refs.size() + " memory references (handoff " + shortId(handoff.id()) + ")");
        if (conversationId != null) {
            ingestion.append(planner, conversationId, MessageRole.AGENT,
                    "Planner created the task \"" + task.title() + "\" and handed it to Developer.", Map.of(), false,
                    "demo-msg-plan-" + task.id());
        }
        return new StepResult("plan", planner.name(), "HANDED_OFF", task.id(), handoff.id(), log,
                Json.map("databaseFact", db.map(MemoryView::content).orElse("none recorded")));
    }

    // ---------------------------------------------------------------- Developer

    public StepResult develop(Actor developer, UUID projectId, UUID testerId, UUID conversationId) {
        List<String> log = new ArrayList<>();
        HandoffView incoming = nextHandoff(developer, projectId)
                .orElseThrow(() -> ApiException.conflict("empty_inbox", "Developer has no handoff for this run; run the Planner step first", Map.of()));
        log.add("Found handoff " + shortId(incoming.id()) + " in inbox (status " + incoming.status() + ")");
        HandoffView claimed = handoffs.acknowledge(developer, incoming.id());
        log.add("Acknowledged it (attempt " + claimed.attempts() + ", lease until " + claimed.leaseExpiresAt() + ")");

        ContextBundle ctx = context.build(developer, new ContextRequest(null, projectId, null, incoming.taskId(), 12, 0, 3000));
        log.add("Built context: " + ctx.stats().memoriesIncluded() + " memories, " + ctx.stats().chars() + " chars (budget "
                + ctx.stats().maxChars() + ")");
        Optional<MemoryView> dbFact = currentFact(developer, projectId, "uses.database");
        String database = dbFact.map(MemoryView::value).orElse("unknown");
        String history = dbFact.map(f -> memories.history(developer, f.id()).versions().stream()
                .map(v -> v.value() + " (" + v.status() + ")").reduce((a, b) -> a + " -> " + b).orElse("")).orElse("");
        log.add("Current database fact: " + database + (history.isEmpty() ? "" : "; history: " + history));

        TaskView task = tasks.get(developer, incoming.taskId());
        if (task.status() == TaskStatus.TODO || task.status() == TaskStatus.FAILED) {
            task = tasks.update(developer, task.id(), new UpdateTaskRequest(task.version(), null, null, TaskStatus.IN_PROGRESS,
                    null, null, null, null, "Developer started work"));
            log.add("Task -> IN_PROGRESS (version " + task.version() + ")");
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        for (String requirement : requirementsOf(task)) {
            checks.add(Json.map("requirement", requirement, "implementedBy", implementationFor(requirement, database)));
        }
        Map<String, Object> implementation = Json.map(
                "endpoint", "GET /health",
                "database", database,
                "databaseCheck", "connectivity probe against " + database + " with a 150 ms timeout",
                "responseFields", List.of("status", "database", "checkedAt"),
                "checks", checks);
        if (task.status() == TaskStatus.IN_PROGRESS) {
            task = tasks.update(developer, task.id(), new UpdateTaskRequest(task.version(), null, null, TaskStatus.IN_REVIEW,
                    null, null, null, Map.of("implementation", implementation), "Implementation ready for testing"));
        }
        log.add("Recorded implementation in shared task state; task -> " + task.status() + " (version " + task.version() + ")");

        MemoryWriteResult finding = memories.create(developer, MemoryDraft.builder(MemoryType.EPISODIC,
                        "Developer implemented GET /health with a " + database + " connectivity check.")
                .category("event").project(projectId).task(task.id()).tags(List.of("event", "implementation"))
                .importance(3).metadata(Json.map("outcome", "success")).source(SourceType.AGENT, null, "developer")
                .build(), "demo-dev-finding-" + task.id());
        handoffs.complete(developer, claimed.id(), Json.map("implemented", true, "database", database));
        log.add("Completed the incoming handoff");

        List<UUID> refs = new ArrayList<>(List.of(finding.memory().id()));
        dbFact.ifPresent(f -> refs.add(f.id()));
        HandoffView outgoing = handoffs.create(developer, new CreateHandoffRequest(task.id(), null, testerId,
                "GET /health is implemented. Verify it against the requirements and the current database fact.",
                refs, List.of("Health check targets " + database + " (current fact" + (history.contains("SUPERSEDED") ? "; older values are superseded" : "") + ")"),
                Map.of("implementation", implementation), "Run the checks and mark the task DONE or FAILED.",
                task.version(), 3), "demo-handoff-dev-" + task.id());
        log.add("Handed off to Tester (handoff " + shortId(outgoing.id()) + ")");
        if (conversationId != null) {
            ingestion.append(developer, conversationId, MessageRole.AGENT,
                    "Developer implemented GET /health against " + database + " and handed it to Tester.", Map.of(), false,
                    "demo-msg-dev-" + task.id());
        }
        return new StepResult("develop", developer.name(), "HANDED_OFF", task.id(), outgoing.id(), log,
                Json.map("database", database, "context", ctx.rendered()));
    }

    // ---------------------------------------------------------------- Tester

    public StepResult test(Actor tester, UUID projectId, boolean failFirstAttempt, UUID conversationId) {
        List<String> log = new ArrayList<>();
        Optional<HandoffView> next = nextHandoff(tester, projectId);
        if (next.isEmpty()) {
            Optional<HandoffView> failed = handoffsFor(tester, projectId).stream()
                    .filter(h -> h.toAgentId().equals(tester.agentId()) && h.status() == HandoffStatus.FAILED)
                    .reduce((a, b) -> b);
            if (failed.isPresent()) {
                HandoffView retried = handoffs.retry(tester, failed.get().id());
                log.add("Found failed handoff " + shortId(retried.id()) + "; retried it (back to " + retried.status() + ")");
                next = Optional.of(retried);
            }
        }
        HandoffView incoming = next.orElseThrow(() -> ApiException.conflict("empty_inbox",
                "Tester has no handoff for this run; run the Developer step first", Map.of()));
        HandoffView claimed = handoffs.acknowledge(tester, incoming.id());
        log.add("Acknowledged handoff " + shortId(claimed.id()) + " (attempt " + claimed.attempts() + " of " + claimed.maxAttempts() + ")");
        TaskView task = tasks.get(tester, claimed.taskId());

        if (failFirstAttempt && claimed.attempts() == 1) {
            String reason = "Test database unavailable (simulated environment failure on first attempt)";
            memories.create(tester, MemoryDraft.builder(MemoryType.EPISODIC, "Tester run failed: " + reason + ".")
                    .category("event").project(projectId).task(task.id()).tags(List.of("event", "failure"))
                    .importance(4).metadata(Json.map("outcome", "failure")).source(SourceType.AGENT, null, "tester")
                    .build(), "demo-test-failure-" + claimed.id());
            memories.create(tester, MemoryDraft.builder(MemoryType.PROCEDURAL,
                            "When the test environment is unavailable, fail the handoff and retry it instead of failing the task.")
                    .category("lesson").project(projectId).tags(List.of("lesson", "testing")).importance(4)
                    .source(SourceType.AGENT, null, "tester").build(), "demo-lesson-" + projectId);
            HandoffView failed = handoffs.fail(tester, claimed.id(), reason, Json.map("attempt", claimed.attempts()));
            log.add("First attempt failed: " + reason);
            log.add("Handoff is now " + failed.status() + "; task stays " + task.status() + ". Run the Tester step again to retry.");
            return new StepResult("test", tester.name(), "FAILED_RETRYABLE", task.id(), failed.id(), log,
                    Json.map("reason", reason, "attempts", failed.attempts()));
        }

        Optional<MemoryView> dbFact = currentFact(tester, projectId, "uses.database");
        @SuppressWarnings("unchecked")
        Map<String, Object> implementation = (Map<String, Object>) task.state().get("implementation");
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(check("Implementation recorded in shared task state", implementation != null, null));
        String implementedDb = implementation == null ? null : String.valueOf(implementation.get("database"));
        String currentDb = dbFact.map(MemoryView::value).orElse(null);
        results.add(check("Health check targets the current database fact", currentDb != null && currentDb.equals(implementedDb),
                "implemented=" + implementedDb + ", current=" + currentDb));
        List<String> requirements = requirementsOf(task);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> covered = implementation == null ? List.of()
                : (List<Map<String, Object>>) implementation.getOrDefault("checks", List.of());
        results.add(check("Every requirement has an implementation note", covered.size() == requirements.size(),
                covered.size() + "/" + requirements.size()));
        boolean passed = results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed")));
        long passedCount = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();

        task = tasks.update(tester, task.id(), new UpdateTaskRequest(task.version(), null, null,
                passed ? TaskStatus.DONE : TaskStatus.FAILED, null, null, null,
                Map.of("testReport", Json.map("passed", passed, "checks", results)),
                passed ? "All checks passed" : "Checks failed"));
        memories.create(tester, MemoryDraft.builder(MemoryType.EPISODIC,
                        "Tester " + (passed ? "verified" : "rejected") + " GET /health: " + passedCount + "/" + results.size() + " checks passed.")
                .category("event").project(projectId).task(task.id()).tags(List.of("event", passed ? "success" : "failure"))
                .importance(3).metadata(Json.map("outcome", passed ? "success" : "failure"))
                .source(SourceType.AGENT, null, "tester").build(), "demo-test-result-" + claimed.id());
        if (passed) {
            handoffs.complete(tester, claimed.id(), Json.map("passed", true, "checks", results));
        } else {
            handoffs.fail(tester, claimed.id(), "Verification failed", Json.map("checks", results));
        }
        log.add(passedCount + "/" + results.size() + " checks passed; task -> " + task.status() + " (version " + task.version() + ")");
        if (conversationId != null) {
            ingestion.append(tester, conversationId, MessageRole.AGENT,
                    "Tester " + (passed ? "verified" : "rejected") + " the /health endpoint (" + passedCount + "/" + results.size() + " checks).",
                    Map.of(), false, "demo-msg-test-" + claimed.id());
        }
        return new StepResult("test", tester.name(), passed ? "DONE" : "FAILED", task.id(), claimed.id(), log,
                Json.map("checks", results));
    }

    // ---------------------------------------------------------------- helpers

    private Optional<HandoffView> nextHandoff(Actor agent, UUID projectId) {
        return handoffs.inbox(agent, agent.agentId(), EnumSet.of(HandoffStatus.PENDING, HandoffStatus.ACKNOWLEDGED)).stream()
                .filter(h -> projectId.equals(tasks.get(agent, h.taskId()).projectId()))
                .findFirst();
    }

    private List<HandoffView> handoffsFor(Actor agent, UUID projectId) {
        return tasks.list(agent, projectId, null, null, 0, 10).items().stream()
                .flatMap(t -> handoffs.forTask(agent, t.id()).stream()).toList();
    }

    private Optional<MemoryView> currentFact(Actor actor, UUID projectId, String predicate) {
        return search.search(actor, MemoryQuery.builder().type(MemoryType.SEMANTIC).project(projectId).limit(100)
                        .sort(MemoryQuery.Sort.RECENT).build()).items().stream()
                .filter(m -> predicate.equals(m.predicate()))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<String> requirementsOf(TaskView task) {
        Object value = task.state().get("requirements");
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static String implementationFor(String requirement, String database) {
        if (requirement.contains("database")) {
            return "Probe " + database + " with a lightweight query and report UP/DOWN";
        }
        if (requirement.contains("200 ms")) {
            return "Probe timeout of 150 ms keeps the endpoint under 200 ms";
        }
        return "Response contains only status fields; no connection details";
    }

    private static Map<String, Object> check(String name, boolean passed, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("passed", passed);
        if (detail != null) {
            m.put("detail", detail);
        }
        return m;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
