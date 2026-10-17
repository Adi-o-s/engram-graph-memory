package com.memora.orchestration.run;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.audit.AuditService;
import com.memora.common.ApiException;
import com.memora.common.Idempotency;
import com.memora.common.Json;
import com.memora.orchestration.agentdef.AgentDefinitionService;
import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.orchestration.executor.StepOutcome;
import com.memora.orchestration.expr.Conditions;
import com.memora.orchestration.expr.Expressions;
import com.memora.orchestration.run.RunDtos.StartRunRequest;
import com.memora.orchestration.workflow.WorkflowDefinition;
import com.memora.orchestration.workflow.WorkflowService;
import com.memora.orchestration.workflow.WorkflowSpec;
import com.memora.orchestration.workflow.WorkflowSpec.InputSpec;
import com.memora.orchestration.workflow.WorkflowSpec.StepSpec;
import com.memora.orchestration.workflow.WorkflowValidator;
import com.memora.project.ProjectService;
import com.memora.project.ProjectService.ProjectView;
import com.memora.security.Actor;
import com.memora.security.Authz;
import com.memora.task.TaskDtos.CreateTaskRequest;
import com.memora.task.TaskService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The generic, database-backed workflow engine. It knows nothing about roles: it reads a pinned spec, decides
 * which steps are ready from their dependencies, conditions and approvals, hands steps to workers through a
 * claim with a lease, records outcomes, applies retry and loop policies, and decides the run's final status
 * from explicit completion criteria.
 *
 * <p>Every state change runs in a short transaction that locks the run row, so two finishing steps cannot
 * both schedule the same successor. Executors run between transactions. Coordination side effects (task
 * board, handoffs) are applied after commit by {@link CoordinationMirror}.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final WorkflowRunRepository runs;
    private final StepRunRepository steps;
    private final ApprovalRepository approvals;
    private final EventLog events;
    private final WorkflowService workflows;
    private final WorkflowValidator validator;
    private final AgentDefinitionService agentDefinitions;
    private final ProjectService projects;
    private final TaskService tasks;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final CoordinationMirror mirror;
    private final WorkflowProperties properties;
    private final TransactionTemplate tx;

    public WorkflowEngine(WorkflowRunRepository runs, StepRunRepository steps, ApprovalRepository approvals, EventLog events,
                          WorkflowService workflows, WorkflowValidator validator, AgentDefinitionService agentDefinitions,
                          ProjectService projects, TaskService tasks, AuditService audit, ObjectMapper objectMapper,
                          JdbcTemplate jdbc, CoordinationMirror mirror, WorkflowProperties properties,
                          PlatformTransactionManager txManager) {
        this.runs = runs;
        this.steps = steps;
        this.approvals = approvals;
        this.events = events;
        this.workflows = workflows;
        this.validator = validator;
        this.agentDefinitions = agentDefinitions;
        this.projects = projects;
        this.tasks = tasks;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.mirror = mirror;
        this.properties = properties;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ start

    public UUID start(Actor actor, UUID workflowId, StartRunRequest request, String idempotencyKey) {
        String key = Idempotency.normalize(idempotencyKey);
        Mirror m = new Mirror();
        UUID runId = Idempotency.createOnce(key,
                () -> tx.execute(s -> runs.findByTenantIdAndIdempotencyKey(actor.tenantId(), key).map(WorkflowRun::getId)),
                () -> tx.execute(s -> startInTransaction(actor, workflowId, request, key, m)));
        mirror.apply(m);
        return runId;
    }

    private UUID startInTransaction(Actor actor, UUID workflowId, StartRunRequest request, String key, Mirror m) {
        if (actor.type() == com.memora.security.ActorType.SYSTEM) {
            throw ApiException.forbidden("Runs are started by a user or an agent");
        }
        WorkflowDefinition w = workflows.require(actor, workflowId);
        if ("ARCHIVED".equals(w.getStatus())) {
            throw ApiException.conflict("workflow_archived", "Workflow " + w.getKey() + " is archived", Map.of());
        }
        int version = request.version() == null ? w.getCurrentVersion() : request.version();
        Map<String, Object> rawSpec = workflows.spec(actor, workflowId, version);
        WorkflowValidator.Result validation = validator.validate(rawSpec, agentDefinitions.allSnapshots(actor),
                agentDefinitions.statusesByKey(actor), true);
        if (!validation.valid()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_workflow",
                    "Workflow " + w.getKey() + " v" + version + " cannot run: " + validation.errors().get(0).path() + " "
                            + validation.errors().get(0).message(), Map.of("errors", validation.errors()));
        }
        WorkflowSpec spec = validation.spec();
        Map<String, AgentSnapshot> agents = agentDefinitions.snapshots(actor,
                spec.stepsOrEmpty().stream().map(StepSpec::agent).distinct().toList());

        Map<String, Object> inputs = resolveInputs(spec, request);
        String goal = request.goal() != null && !request.goal().isBlank() ? request.goal()
                : inputs.get("goal") == null ? null : String.valueOf(inputs.get("goal"));
        Map<String, Object> options = validateOptions(actor, spec, request.options());

        UUID projectId = request.projectId() != null ? request.projectId() : w.getProjectId();
        if (projectId != null) {
            projects.requireInTenant(actor, projectId);
        } else {
            String stamp = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("MMdd-HHmmss"));
            ProjectView p = projects.create(Actor.system(actor.tenantId()),
                    "run-" + stamp + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xffff)),
                    w.getName() + " " + stamp, goal == null ? "Workspace created for a run of " + w.getName() : abbreviate(goal, 500));
            projectId = p.id();
        }

        String name = w.getName() + (goal == null ? "" : ": " + abbreviate(goal, 120));
        Map<String, Object> agentMaps = new LinkedHashMap<>();
        agents.forEach((k, a) -> agentMaps.put(k, objectMapper.convertValue(a, MAP)));
        WorkflowRun run = new WorkflowRun(actor.tenantId(), projectId, w.getId(), version, abbreviate(name, 300), goal, inputs,
                rawSpec, agentMaps, options, actor.userId(), key);
        run.setStatus(RunStatus.RUNNING);
        runs.save(run);
        runs.flush();

        UUID umbrella = tasks.create(actor, new CreateTaskRequest(projectId, abbreviate("Run: " + name, 300),
                "Workflow " + w.getKey() + " v" + version + (goal == null ? "" : "\n\nGoal: " + goal), 2, null,
                Json.map("workflowRunId", run.getId().toString(), "kind", "workflow-run")), null).id();
        run.setTaskId(umbrella);

        List<StepRun> created = new ArrayList<>();
        for (StepSpec s : spec.stepsOrEmpty()) {
            if (!s.isRemediation()) {
                created.add(newStep(actor, run, spec, agents, s, 1, s.deps()));
            }
        }
        runs.flush();
        events.append(run.getTenantId(), run.getId(), null, "run_started",
                "Started " + w.getName() + " v" + version + " with " + created.size() + " step(s)",
                Json.map("workflowKey", w.getKey(), "version", version, "agents",
                        agents.values().stream().map(a -> a.key() + "@v" + a.version()).toList(),
                        "options", options.isEmpty() ? null : options));
        audit.record(actor, "workflow_run.started", "workflow_run", run.getId(), Json.map("workflow", w.getKey(), "version", version));
        m.run(Mirror.Kind.RUN_STARTED, run);
        advance(run, created, m);
        return run.getId();
    }

    private StepRun newStep(Actor actor, WorkflowRun run, WorkflowSpec spec, Map<String, AgentSnapshot> agents, StepSpec s,
                            int iteration, List<String> dependsOn) {
        AgentSnapshot agent = agents.get(s.agent());
        int maxAttempts = s.retry() != null && s.retry().maxAttempts() != null ? s.retry().maxAttempts() : agent.maxAttempts();
        UUID taskId = tasks.create(actor, new CreateTaskRequest(run.getProjectId(),
                abbreviate(s.displayName() + (iteration > 1 || s.isRemediation() ? " (iteration " + iteration + ")" : ""), 300),
                (s.description() == null ? "" : s.description() + "\n\n") + "Workflow step " + s.key() + " of run " + run.getName(),
                3, agent.agentId(), Json.map("workflowRunId", run.getId().toString(), "stepKey", s.key(), "iteration", iteration)),
                null).id();
        StepRun step = new StepRun(run.getTenantId(), run.getId(), s.key(), iteration, s.displayName(), agent.definitionId(),
                agent.key(), agent.version(), agent.executor(), dependsOn, maxAttempts, taskId);
        steps.save(step);
        return step;
    }

    private Map<String, Object> resolveInputs(WorkflowSpec spec, StartRunRequest request) {
        Map<String, Object> given = request.inputs() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.inputs());
        if (request.goal() != null && !request.goal().isBlank() && spec.inputsOrEmpty().stream().anyMatch(i -> "goal".equals(i.name()))) {
            given.putIfAbsent("goal", request.goal());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (InputSpec in : spec.inputsOrEmpty()) {
            Object v = given.containsKey(in.name()) ? given.get(in.name()) : in.defaultValue();
            if (v == null || (v instanceof String s && s.isBlank())) {
                if (in.isRequired()) {
                    problems.add("input '" + in.name() + "' is required");
                }
                continue;
            }
            String type = in.type() == null ? "string" : in.type();
            boolean ok = switch (type) {
                case "string", "text" -> v instanceof String;
                case "number" -> v instanceof Number;
                case "boolean" -> v instanceof Boolean;
                case "object" -> v instanceof Map;
                case "array" -> v instanceof Collection;
                default -> true;
            };
            if (!ok) {
                problems.add("input '" + in.name() + "' must be a " + type);
            }
            out.put(in.name(), v);
        }
        for (String k : given.keySet()) {
            if (spec.inputsOrEmpty().stream().noneMatch(i -> k.equals(i.name()))) {
                problems.add("input '" + k + "' is not declared by this workflow");
            }
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_inputs", String.join("; ", problems),
                    Map.of("problems", problems));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateOptions(Actor actor, WorkflowSpec spec, Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (String k : raw.keySet()) {
            if (!k.equals("pacingMs") && !k.equals("faults")) {
                throw ApiException.badRequest("Unknown run option '" + k + "' (allowed: pacingMs, faults)");
            }
        }
        if (raw.get("pacingMs") != null) {
            if (!(raw.get("pacingMs") instanceof Number n) || n.intValue() < 0 || n.intValue() > properties.maxPacingMs()) {
                throw ApiException.badRequest("pacingMs must be between 0 and " + properties.maxPacingMs());
            }
            out.put("pacingMs", n.intValue());
        }
        if (raw.get("faults") instanceof Map<?, ?> faults && !faults.isEmpty()) {
            Authz.requireAdmin(actor);
            Map<String, Object> clean = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : faults.entrySet()) {
                String stepKey = String.valueOf(e.getKey());
                if (spec.step(stepKey).isEmpty()) {
                    throw ApiException.badRequest("Fault injection names unknown step '" + stepKey + "'");
                }
                Map<String, Object> f = e.getValue() instanceof Map<?, ?> fm ? (Map<String, Object>) fm : Map.of();
                int failAttempts = f.get("failAttempts") instanceof Number n ? n.intValue() : 1;
                if (failAttempts < 1 || failAttempts > 10) {
                    throw ApiException.badRequest("failAttempts must be between 1 and 10");
                }
                clean.put(stepKey, Json.map("failAttempts", failAttempts, "retryable", !Boolean.FALSE.equals(f.get("retryable")),
                        "message", f.get("message") == null ? "simulated transient failure" : abbreviate(String.valueOf(f.get("message")), 200)));
            }
            out.put("faults", clean);
        }
        return out;
    }

    // ------------------------------------------------------------------ scheduling

    public record Claimed(UUID stepRunId, UUID tenantId, UUID runId, int attempt) {
    }

    /** Claims READY steps of RUNNING runs across tenants. Caller must be in system scope. */
    public List<Claimed> claim(String owner, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return tx.execute(s -> jdbc.query("""
                UPDATE workflow_step_runs s
                SET status = 'RUNNING', lease_owner = ?, lease_expires_at = now() + interval '600 seconds',
                    attempts = s.attempts + 1, started_at = now(), next_attempt_at = NULL, lock_version = s.lock_version + 1
                WHERE s.id IN (
                    SELECT s2.id FROM workflow_step_runs s2
                    JOIN workflow_runs r ON r.id = s2.run_id AND r.tenant_id = s2.tenant_id
                    WHERE s2.status = 'READY' AND (s2.next_attempt_at IS NULL OR s2.next_attempt_at <= now())
                      AND r.status = 'RUNNING'
                    ORDER BY s2.ready_at NULLS FIRST, s2.created_at
                    LIMIT ?
                    FOR UPDATE OF s2 SKIP LOCKED)
                RETURNING s.id, s.tenant_id, s.run_id, s.attempts
                """, (rs, i) -> new Claimed(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getInt("attempts")), owner, limit));
    }

    /** Everything a worker needs to execute one claimed step, read in the step's tenant scope. */
    public record Prepared(UUID tenantId, UUID runId, UUID projectId, UUID workflowId, UUID stepRunId, String stepKey,
                           int iteration, int attempt, UUID startedByUserId, AgentSnapshot agent, Map<String, Object> input,
                           Map<String, Object> view, int timeoutSeconds, int pacingMs, Map<String, Object> fault,
                           boolean approved) {
    }

    public Optional<Prepared> prepare(UUID tenantId, UUID stepRunId, String owner, int attempt) {
        Mirror m = new Mirror();
        Optional<Prepared> prepared = tx.execute(s -> {
            StepRun step = steps.findByTenantIdAndId(tenantId, stepRunId).orElse(null);
            if (step == null || step.getStatus() != StepStatus.RUNNING || !owner.equals(step.getLeaseOwner())
                    || step.getAttempts() != attempt) {
                return Optional.<Prepared>empty();
            }
            WorkflowRun run = runs.findByTenantIdAndId(tenantId, step.getRunId()).orElseThrow();
            WorkflowSpec spec = spec(run);
            AgentSnapshot agent = agents(run).get(step.getAgentKey());
            StepSpec stepSpec = spec.step(step.getStepKey()).orElseThrow();
            int timeout = stepSpec.timeoutSeconds() != null ? stepSpec.timeoutSeconds() : agent.timeoutSeconds();
            step.extendLease(Instant.now().plusSeconds(timeout + properties.leaseGraceSeconds()));
            List<StepRun> all = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(tenantId, run.getId());
            Map<String, Object> view = view(run, all);
            Map<String, Object> input = Expressions.resolveMap(stepSpec.inputs() == null ? Map.of() : stepSpec.inputs(), view);
            step.setInput(input);
            Map<String, Object> stepView = Json.map("key", step.getStepKey(), "name", step.getName(), "iteration",
                    step.getIteration(), "attempt", step.getAttempts(), "id", step.getId().toString());
            view.put("input", input);
            view.put("agent", Json.map("key", agent.key(), "name", agent.name(), "role", agent.role(),
                    "instructions", agent.instructions()));
            view.put("step", stepView);
            boolean approved = approvals.findByTenantIdAndStepRunId(tenantId, step.getId())
                    .map(a -> "APPROVED".equals(a.getStatus())).orElse(false);
            @SuppressWarnings("unchecked")
            Map<String, Object> faults = (Map<String, Object>) run.getOptions().getOrDefault("faults", Map.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> fault = (Map<String, Object>) faults.get(step.getStepKey());
            int pacing = run.getOptions().get("pacingMs") instanceof Number n ? n.intValue() : 0;
            events.append(tenantId, run.getId(), step.getId(), "step_started",
                    step.getName() + " started (attempt " + step.getAttempts() + " of " + step.getMaxAttempts() + ") by "
                            + agent.name() + " via " + agent.executor(),
                    Json.map("attempt", step.getAttempts(), "executor", agent.executor(), "agentVersion", agent.version(),
                            "worker", owner));
            m.add(Mirror.Kind.STARTED, step, null);
            return Optional.of(new Prepared(tenantId, run.getId(), run.getProjectId(), run.getWorkflowId(), step.getId(),
                    step.getStepKey(), step.getIteration(), step.getAttempts(), run.getStartedByUserId(), agent, input, view,
                    timeout, pacing, fault, approved));
        });
        mirror.apply(m);
        return prepared;
    }

    /** Records an executor outcome. Fenced: ignored unless this worker still holds the claim for this attempt. */
    public void complete(UUID tenantId, UUID stepRunId, String owner, int attempt, StepOutcome outcome) {
        Mirror m = new Mirror();
        tx.executeWithoutResult(s -> {
            UUID runId = runIdOf(tenantId, stepRunId);
            if (runId == null) {
                return;
            }
            // Lock the run before reading the step, so the step state we check is the committed one.
            WorkflowRun run = runs.lock(tenantId, runId).orElseThrow();
            StepRun step = steps.findByTenantIdAndId(tenantId, stepRunId).orElseThrow();
            if (step.getStatus() != StepStatus.RUNNING || !owner.equals(step.getLeaseOwner()) || step.getAttempts() != attempt) {
                events.append(tenantId, run.getId(), step.getId(), "stale_completion_ignored",
                        "Ignored a result for " + step.getName() + " attempt " + attempt + " from " + owner
                                + ": the step is " + step.getStatus() + " (attempt " + step.getAttempts() + ")",
                        Json.map("owner", owner, "attempt", attempt, "currentStatus", step.getStatus().name()));
                return;
            }
            record(run, step, outcome, m);
        });
        mirror.apply(m);
    }

    private void record(WorkflowRun run, StepRun step, StepOutcome outcome, Mirror m) {
        if (outcome.log() != null && !outcome.log().isEmpty()) {
            events.append(run.getTenantId(), run.getId(), step.getId(), "step_log", step.getName() + " log (" + outcome.log().size()
                    + " line(s))", Json.map("lines", outcome.log(), "attempt", step.getAttempts()));
        }
        List<StepRun> all = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(run.getTenantId(), run.getId());
        if (run.getStatus() == RunStatus.CANCELLED) {
            step.markCancelled();
            events.append(run.getTenantId(), run.getId(), step.getId(), "step_cancelled",
                    step.getName() + " finished after the run was cancelled; its result was discarded", Map.of());
            m.add(Mirror.Kind.FINISHED, step, "run cancelled");
            return;
        }
        WorkflowSpec spec = spec(run);
        StepSpec stepSpec = spec.step(step.getStepKey()).orElseThrow();
        AgentSnapshot agent = agents(run).get(step.getAgentKey());
        long durationMs = step.getStartedAt() == null ? 0 : java.time.Duration.between(step.getStartedAt(), Instant.now()).toMillis();
        switch (outcome.status()) {
            case WAITING_EXTERNAL -> {
                events.append(run.getTenantId(), run.getId(), step.getId(), "step_waiting_external",
                        step.getName() + " was sent to " + agent.agentName() + "'s inbox; waiting for the agent to report back",
                        Json.map("leaseExpiresAt", String.valueOf(step.getLeaseExpiresAt())));
                m.add(Mirror.Kind.DISPATCH, step, null);
                return;
            }
            case SUCCEEDED -> {
                Map<String, Object> output = outcome.output() == null ? Map.of() : outcome.output();
                if (stepSpec.loop() != null) {
                    Map<String, Object> loopView = view(run, all);
                    loopView.put("output", output);
                    Conditions.Evaluation ev = Conditions.explain(stepSpec.loop().until(), loopView);
                    if (!ev.result()) {
                        if (step.getIteration() < stepSpec.loop().max()) {
                            step.markSucceeded(output);
                            events.append(run.getTenantId(), run.getId(), step.getId(), "loop_continue",
                                    step.getName() + " iteration " + step.getIteration() + " finished but its exit condition is "
                                            + "false; running " + stepSpec.loop().remediationStep() + " and then iteration "
                                            + (step.getIteration() + 1) + " of " + stepSpec.loop().max(),
                                    Json.map("trace", ev.trace(), "iteration", step.getIteration(), "durationMs", durationMs));
                            m.add(Mirror.Kind.FINISHED, step, null);
                            StepSpec rem = spec.step(stepSpec.loop().remediationStep()).orElseThrow();
                            Actor system = Actor.system(run.getTenantId());
                            Map<String, AgentSnapshot> agentMap = agents(run);
                            int n = step.getIteration();
                            newStep(system, run, spec, agentMap, rem, n, List.of(step.getStepKey() + "#" + n));
                            newStep(system, run, spec, agentMap, stepSpec, n + 1, List.of(rem.key() + "#" + n));
                            steps.flush();
                            all = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(run.getTenantId(), run.getId());
                            advance(run, all, m);
                            return;
                        }
                        step.markFailed("Exit condition still false after " + step.getIteration() + " iteration(s): "
                                + String.join("; ", ev.trace()), "PERMANENT", output);
                        events.append(run.getTenantId(), run.getId(), step.getId(), "loop_exhausted",
                                step.getName() + " reached its iteration limit (" + stepSpec.loop().max()
                                        + ") without meeting its exit condition", Json.map("trace", ev.trace()));
                        m.add(Mirror.Kind.FINISHED, step, step.getError());
                        advance(run, all, m);
                        return;
                    }
                    events.append(run.getTenantId(), run.getId(), step.getId(), "loop_exit",
                            step.getName() + " met its exit condition on iteration " + step.getIteration(), Json.map("trace", ev.trace()));
                }
                step.markSucceeded(output);
                events.append(run.getTenantId(), run.getId(), step.getId(), "step_succeeded",
                        step.getName() + " succeeded in " + durationMs + " ms (attempt " + step.getAttempts() + ")",
                        Json.map("durationMs", durationMs, "outputKeys", new ArrayList<>(output.keySet())));
                m.add(Mirror.Kind.FINISHED, step, null);
            }
            case FAILED -> {
                boolean retryable = outcome.failureKind() == StepOutcome.FailureKind.RETRYABLE;
                if (retryable && step.getAttempts() < step.getMaxAttempts()) {
                    int base = stepSpec.retry() != null && stepSpec.retry().backoffSeconds() != null
                            ? stepSpec.retry().backoffSeconds() : agent.backoffSeconds();
                    long delay = Math.min(300, (long) base * (1L << Math.min(10, step.getAttempts() - 1)));
                    Instant at = Instant.now().plusSeconds(delay);
                    step.scheduleRetry(outcome.error(), at);
                    events.append(run.getTenantId(), run.getId(), step.getId(), "retry_scheduled",
                            step.getName() + " attempt " + step.getAttempts() + " failed: " + outcome.error() + ". Retrying in "
                                    + delay + " s (attempt " + (step.getAttempts() + 1) + " of " + step.getMaxAttempts() + ")",
                            Json.map("attempt", step.getAttempts(), "error", outcome.error(), "nextAttemptAt", at.toString()));
                    m.add(Mirror.Kind.ATTEMPT_FAILED, step, outcome.error());
                } else {
                    String kind = retryable ? "RETRIES_EXHAUSTED" : "PERMANENT";
                    step.markFailed(outcome.error(), kind, outcome.output());
                    events.append(run.getTenantId(), run.getId(), step.getId(), "step_failed",
                            step.getName() + " failed" + (retryable ? " after " + step.getAttempts() + " attempt(s)" : " (not retryable)")
                                    + ": " + outcome.error(), Json.map("attempts", step.getAttempts(), "kind", kind));
                    m.add(Mirror.Kind.FINISHED, step, outcome.error());
                }
            }
        }
        advance(run, all, m);
    }

    // ------------------------------------------------------------------ advancing

    /** Decides what can run next and the run's status. Caller holds the run lock. */
    void advance(WorkflowRun run, List<StepRun> all, Mirror m) {
        if (run.getStatus() == RunStatus.CANCELLED) {
            return;
        }
        WorkflowSpec spec = spec(run);
        boolean changed = true;
        for (int guard = 0; changed && guard < 500; guard++) {
            changed = false;
            Map<String, StepRun> latest = latest(all);
            for (StepRun s : latest.values()) {
                if (s.getStatus() == StepStatus.PENDING) {
                    List<Map<String, Object>> deps = new ArrayList<>();
                    StepRun failedDep = null;
                    boolean allSatisfied = true;
                    for (String d : s.getDependsOn()) {
                        StepRun dep = resolveDep(all, latest, d);
                        if (dep == null) {
                            allSatisfied = false;
                            continue;
                        }
                        deps.add(Json.map("step", dep.getStepKey(), "iteration", dep.getIteration(), "status", dep.getStatus().name(),
                                "finishedAt", dep.getFinishedAt() == null ? null : dep.getFinishedAt().toString()));
                        if (dep.getStatus() == StepStatus.FAILED || dep.getStatus() == StepStatus.CANCELLED
                                || dep.getStatus() == StepStatus.BLOCKED) {
                            failedDep = dep;
                        } else if (!StepStatus.SATISFIED.contains(dep.getStatus())) {
                            allSatisfied = false;
                        }
                    }
                    if (failedDep != null) {
                        s.markBlocked("Waiting on " + failedDep.getName() + " (" + failedDep.getStatus() + ")");
                        events.append(run.getTenantId(), run.getId(), s.getId(), "step_blocked",
                                s.getName() + " is blocked because " + failedDep.getName() + " is " + failedDep.getStatus(),
                                Json.map("blockedBy", failedDep.getStepKey()));
                        m.add(Mirror.Kind.BLOCKED, s, s.getError());
                        changed = true;
                        continue;
                    }
                    if (!allSatisfied) {
                        continue;
                    }
                    StepSpec stepSpec = spec.step(s.getStepKey()).orElseThrow();
                    Map<String, Object> reason = new LinkedHashMap<>();
                    reason.put("dependencies", deps);
                    reason.put("evaluatedAt", Instant.now().toString());
                    if (deps.isEmpty()) {
                        reason.put("note", s.getIteration() > 1 || stepSpec.isRemediation() ? "loop iteration" : "root step: no dependencies");
                    }
                    if (stepSpec.when() != null && !stepSpec.when().isEmpty()) {
                        Conditions.Evaluation ev = Conditions.explain(stepSpec.when(), view(run, all));
                        reason.put("condition", Json.map("result", ev.result(), "trace", ev.trace()));
                        if (!ev.result()) {
                            s.markSkipped(reason);
                            events.append(run.getTenantId(), run.getId(), s.getId(), "step_skipped",
                                    s.getName() + " skipped: its condition is false (" + String.join("; ", ev.trace()) + ")", reason);
                            m.add(Mirror.Kind.FINISHED, s, "skipped: condition false");
                            changed = true;
                            continue;
                        }
                    }
                    if (stepSpec.needsApproval()) {
                        Optional<WorkflowApproval> approval = approvals.findByTenantIdAndStepRunId(run.getTenantId(), s.getId());
                        if (approval.isEmpty() || !"APPROVED".equals(approval.get().getStatus())) {
                            if (approval.isEmpty()) {
                                approvals.save(new WorkflowApproval(run.getTenantId(), run.getId(), s.getId(),
                                        stepSpec.approval().reason() == null ? "Approval required before " + s.getName()
                                                : stepSpec.approval().reason()));
                            }
                            reason.put("approval", "required");
                            s.markWaitingApproval(reason);
                            events.append(run.getTenantId(), run.getId(), s.getId(), "approval_requested",
                                    s.getName() + " is waiting for approval", reason);
                            changed = true;
                            continue;
                        }
                        reason.put("approval", "approved");
                    }
                    s.markReady(reason);
                    events.append(run.getTenantId(), run.getId(), s.getId(), "step_ready",
                            s.getName() + " is ready: " + (deps.isEmpty() ? "no dependencies"
                                    : "all " + deps.size() + " dependenc" + (deps.size() == 1 ? "y" : "ies") + " satisfied"), reason);
                    m.add(Mirror.Kind.DISPATCH, s, null);
                    changed = true;
                } else if (s.getStatus() == StepStatus.BLOCKED) {
                    boolean stillBlocked = s.getDependsOn().stream().map(d -> resolveDep(all, latest, d))
                            .anyMatch(dep -> dep != null && (dep.getStatus() == StepStatus.FAILED
                                    || dep.getStatus() == StepStatus.CANCELLED || dep.getStatus() == StepStatus.BLOCKED));
                    if (!stillBlocked) {
                        s.markPending();
                        events.append(run.getTenantId(), run.getId(), s.getId(), "step_unblocked",
                                s.getName() + " is no longer blocked", Map.of());
                        changed = true;
                    }
                }
            }
        }
        updateRunStatus(run, spec, all, m);
    }

    private void updateRunStatus(WorkflowRun run, WorkflowSpec spec, List<StepRun> all, Mirror m) {
        if (run.getStatus() == RunStatus.CANCELLED || run.getStatus() == RunStatus.PAUSED) {
            return;
        }
        RunStatus before = run.getStatus();
        Map<String, StepRun> latest = latest(all);
        Collection<StepRun> current = latest.values();
        RunStatus next;
        Map<String, Object> completion = null;
        if (current.stream().anyMatch(s -> s.getStatus() == StepStatus.RUNNING || s.getStatus() == StepStatus.READY)) {
            next = RunStatus.RUNNING;
        } else if (current.stream().anyMatch(s -> s.getStatus() == StepStatus.WAITING_APPROVAL)) {
            next = RunStatus.WAITING_APPROVAL;
        } else if (current.stream().anyMatch(s -> s.getStatus() == StepStatus.FAILED)) {
            next = RunStatus.FAILED;
            completion = completionReport(run, spec, all, false);
        } else if (current.stream().anyMatch(s -> s.getStatus() == StepStatus.PENDING || s.getStatus() == StepStatus.BLOCKED)) {
            next = RunStatus.BLOCKED;
        } else {
            completion = completionReport(run, spec, all, true);
            next = Boolean.TRUE.equals(completion.get("passed")) ? RunStatus.SUCCEEDED : RunStatus.FAILED;
        }
        if (next != before || (completion != null && run.getCompletion() == null)) {
            run.setStatus(next);
            run.setCompletion(completion);
            if (next.isFinished() && spec.outputs() != null) {
                try {
                    run.setOutputs(Expressions.resolveMap(spec.outputs(), view(run, all)));
                } catch (RuntimeException e) {
                    run.setOutputs(Json.map("error", "Could not compute outputs: " + e.getMessage()));
                }
            }
            if (next != before) {
                events.append(run.getTenantId(), run.getId(), null, "run_" + next.name().toLowerCase(),
                        "Run is now " + next + (completion != null && completion.get("summary") != null ? ": " + completion.get("summary") : ""),
                        completion == null ? Map.of() : Json.map("summary", completion.get("summary")));
                if (next.isFinished()) {
                    m.run(Mirror.Kind.RUN_FINISHED, run);
                }
            }
        }
    }

    /** Evaluates required steps, mandatory steps and completion criteria against actual step outputs. */
    private Map<String, Object> completionReport(WorkflowRun run, WorkflowSpec spec, List<StepRun> all, boolean allFinished) {
        Map<String, StepRun> latest = latest(all);
        Map<String, Object> view = view(run, all);
        List<Map<String, Object>> required = new ArrayList<>();
        boolean passed = allFinished;
        if (spec.completion() != null) {
            for (String key : spec.completion().required()) {
                StepRun s = latest.get(key);
                boolean ok = s != null && s.getStatus() == StepStatus.SUCCEEDED;
                passed &= ok;
                required.add(Json.map("step", key, "status", s == null ? "NOT_RUN" : s.getStatus().name(), "passed", ok));
            }
        }
        for (StepSpec s : spec.stepsOrEmpty()) {
            StepRun r = latest.get(s.key());
            if (s.isMandatory() && !s.isRemediation() && (r == null || !StepStatus.SATISFIED.contains(r.getStatus()))) {
                passed = false;
            }
        }
        List<Map<String, Object>> criteria = new ArrayList<>();
        if (spec.completion() != null) {
            for (WorkflowSpec.CriterionSpec c : spec.completion().criteriaOrEmpty()) {
                Conditions.Evaluation ev;
                try {
                    ev = Conditions.explain(c.condition(), view);
                } catch (RuntimeException e) {
                    ev = new Conditions.Evaluation(false, List.of("error: " + e.getMessage()));
                }
                passed &= ev.result();
                criteria.add(Json.map("name", c.name(), "description", c.description(), "passed", ev.result(), "trace", ev.trace()));
            }
        }
        List<Map<String, Object>> outstanding = new ArrayList<>();
        for (StepRun s : latest.values()) {
            if (!StepStatus.SATISFIED.contains(s.getStatus())) {
                outstanding.add(Json.map("step", s.getStepKey(), "name", s.getName(), "status", s.getStatus().name(),
                        "reason", s.getError()));
            }
        }
        long failedCriteria = criteria.stream().filter(c -> !Boolean.TRUE.equals(c.get("passed"))).count();
        String summary = passed ? "all completion criteria met (" + criteria.size() + " criteria, " + required.size() + " required steps)"
                : !allFinished ? outstanding.size() + " step(s) did not finish: "
                        + outstanding.stream().map(o -> o.get("step") + " " + o.get("status")).toList()
                : failedCriteria + " of " + criteria.size() + " completion criteria not met"
                        + (outstanding.isEmpty() ? "" : "; outstanding: " + outstanding.stream().map(o -> o.get("step")).toList());
        return Json.map("passed", passed, "evaluatedAt", Instant.now().toString(), "requiredSteps", required,
                "criteria", criteria, "outstanding", outstanding, "summary", summary);
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Steps whose lease expired (worker crashed, process restarted, external agent silent) are retried if they
     * have attempts left, otherwise failed. With {@code startup}, steps claimed by a previous process are
     * treated as expired immediately (single-node deployments).
     */
    public int recoverExpired(String currentOwnerPrefix, boolean startup) {
        List<Map<String, Object>> expired = jdbc.queryForList("""
                SELECT id, tenant_id, lease_owner, executor FROM workflow_step_runs
                WHERE status = 'RUNNING' AND (lease_expires_at < now() OR (? AND (lease_owner IS NULL OR lease_owner NOT LIKE ?)
                      AND coalesce(executor, '') <> 'external'))
                LIMIT 200
                """, startup, currentOwnerPrefix + "%");
        int n = 0;
        for (Map<String, Object> row : expired) {
            UUID tenantId = (UUID) row.get("tenant_id");
            UUID stepId = (UUID) row.get("id");
            String leaseOwner = (String) row.get("lease_owner");
            try {
                if (com.memora.security.TenantContext.callAsTenant(tenantId, () -> expire(tenantId, stepId, leaseOwner, startup))) {
                    n++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not recover step {}: {}", stepId, e.getMessage());
            }
        }
        return n;
    }

    private boolean expire(UUID tenantId, UUID stepId, String leaseOwner, boolean startup) {
        Mirror m = new Mirror();
        Boolean done = tx.execute(s -> {
            UUID runId = runIdOf(tenantId, stepId);
            if (runId == null) {
                return false;
            }
            WorkflowRun run = runs.lock(tenantId, runId).orElseThrow();
            StepRun step = steps.findByTenantIdAndId(tenantId, stepId).orElseThrow();
            if (step.getStatus() != StepStatus.RUNNING || (leaseOwner != null && !leaseOwner.equals(step.getLeaseOwner()))) {
                return false;
            }
            String why = "external".equals(step.getExecutor())
                    ? "The external agent did not report back before the step timeout"
                    : startup ? "The backend restarted while this step was running (worker " + leaseOwner + ")"
                    : "The worker's lease expired at " + step.getLeaseExpiresAt() + " without a result";
            events.append(tenantId, run.getId(), step.getId(), "lease_expired", step.getName() + ": " + why,
                    Json.map("leaseOwner", leaseOwner, "attempt", step.getAttempts()));
            record(run, step, StepOutcome.failed(StepOutcome.FailureKind.RETRYABLE, why, List.of()), m);
            return true;
        });
        mirror.apply(m);
        return Boolean.TRUE.equals(done);
    }

    // ------------------------------------------------------------------ operator actions

    public void pause(Actor actor, UUID runId) {
        operate(actor, runId, (run, all, m) -> {
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.WAITING_APPROVAL
                    && run.getStatus() != RunStatus.BLOCKED) {
                throw ApiException.conflict("invalid_run_state", "Only an active run can be paused (run is " + run.getStatus() + ")", Map.of());
            }
            run.setStatus(RunStatus.PAUSED);
            events.append(run.getTenantId(), run.getId(), null, "run_paused",
                    "Paused by " + actor.name() + ": no new steps start; steps already running finish and are recorded",
                    Json.map("by", actor.name()));
            audit.record(actor, "workflow_run.paused", "workflow_run", run.getId(), Map.of());
        });
    }

    public void resume(Actor actor, UUID runId) {
        operate(actor, runId, (run, all, m) -> {
            if (run.getStatus() != RunStatus.PAUSED && run.getStatus() != RunStatus.BLOCKED && run.getStatus() != RunStatus.FAILED) {
                throw ApiException.conflict("invalid_run_state", "Only a paused, blocked or failed run can be resumed (run is "
                        + run.getStatus() + ")", Map.of());
            }
            long done = all.stream().filter(s -> s.getStatus() == StepStatus.SUCCEEDED).count();
            run.setStatus(RunStatus.RUNNING);
            run.setCompletion(null);
            events.append(run.getTenantId(), run.getId(), null, "run_resumed", "Resumed by " + actor.name() + "; " + done
                    + " completed step(s) are kept and not re-run", Json.map("by", actor.name(), "completedSteps", done));
            audit.record(actor, "workflow_run.resumed", "workflow_run", run.getId(), Map.of());
            advance(run, all, m);
        });
    }

    public void cancel(Actor actor, UUID runId) {
        operate(actor, runId, (run, all, m) -> {
            if (run.getStatus().isFinished()) {
                throw ApiException.conflict("invalid_run_state", "The run already finished (" + run.getStatus() + ")", Map.of());
            }
            run.setStatus(RunStatus.CANCELLED);
            int cancelled = 0;
            for (StepRun s : all) {
                if (s.getStatus() == StepStatus.PENDING || s.getStatus() == StepStatus.READY
                        || s.getStatus() == StepStatus.WAITING_APPROVAL || s.getStatus() == StepStatus.BLOCKED) {
                    s.markCancelled();
                    m.add(Mirror.Kind.FINISHED, s, "run cancelled");
                    cancelled++;
                }
            }
            approvals.findByTenantIdAndRunIdOrderByCreatedAtAsc(run.getTenantId(), run.getId()).stream()
                    .filter(a -> "PENDING".equals(a.getStatus())).forEach(a -> a.decide(false, actor.userId(), "Run cancelled"));
            long running = all.stream().filter(s -> s.getStatus() == StepStatus.RUNNING).count();
            run.setCompletion(Json.map("passed", false, "summary", "Cancelled by " + actor.name(), "evaluatedAt", Instant.now().toString()));
            events.append(run.getTenantId(), run.getId(), null, "run_cancelled", "Cancelled by " + actor.name() + "; " + cancelled
                    + " step(s) cancelled" + (running > 0 ? ", " + running + " running step(s) will be discarded when they finish" : ""),
                    Json.map("by", actor.name()));
            audit.record(actor, "workflow_run.cancelled", "workflow_run", run.getId(), Map.of());
            m.run(Mirror.Kind.RUN_FINISHED, run);
        });
    }

    public void retryStep(Actor actor, UUID runId, UUID stepRunId) {
        operate(actor, runId, (run, all, m) -> {
            if (run.getStatus() == RunStatus.CANCELLED) {
                throw ApiException.conflict("invalid_run_state", "The run was cancelled", Map.of());
            }
            StepRun step = all.stream().filter(s -> s.getId().equals(stepRunId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Step", stepRunId));
            if (latest(all).get(step.getStepKey()) != step) {
                throw ApiException.conflict("not_latest_iteration", "Only the latest iteration of a step can be retried", Map.of());
            }
            if (step.getStatus() != StepStatus.FAILED) {
                throw ApiException.conflict("invalid_step_state", "Only a FAILED step can be retried (step is " + step.getStatus() + ")",
                        Map.of("status", step.getStatus()));
            }
            step.grantAttempt();
            step.markReady(Json.map("manualRetry", true, "by", actor.name(), "previousError", step.getError(),
                    "evaluatedAt", Instant.now().toString()));
            if (run.getStatus() != RunStatus.PAUSED) {
                run.setStatus(RunStatus.RUNNING);
                run.setCompletion(null);
            }
            events.append(run.getTenantId(), run.getId(), step.getId(), "step_retry_requested",
                    step.getName() + " will be retried (requested by " + actor.name() + "); attempt " + (step.getAttempts() + 1)
                            + " of " + step.getMaxAttempts(), Json.map("by", actor.name()));
            audit.record(actor, "workflow_step.retried", "workflow_step", step.getId(), Json.map("runId", run.getId()));
            m.add(Mirror.Kind.DISPATCH, step, null);
            advance(run, all, m);
        });
    }

    public void decide(Actor actor, UUID runId, UUID stepRunId, boolean approve, String note) {
        Authz.requireAdmin(actor);
        Authz.requireUser(actor);
        operate(actor, runId, (run, all, m) -> {
            StepRun step = all.stream().filter(s -> s.getId().equals(stepRunId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Step", stepRunId));
            if (step.getStatus() != StepStatus.WAITING_APPROVAL) {
                throw ApiException.conflict("invalid_step_state", "Step is not waiting for approval (" + step.getStatus() + ")", Map.of());
            }
            WorkflowApproval approval = approvals.findByTenantIdAndStepRunId(run.getTenantId(), step.getId()).orElseThrow();
            approval.decide(approve, actor.userId(), note);
            if (approve) {
                step.markPending();
                events.append(run.getTenantId(), run.getId(), step.getId(), "approval_granted",
                        step.getName() + " approved by " + actor.name() + (note == null ? "" : ": " + note), Json.map("by", actor.name()));
            } else {
                step.markFailed("Rejected by " + actor.name() + (note == null ? "" : ": " + note), "PERMANENT", null);
                events.append(run.getTenantId(), run.getId(), step.getId(), "approval_rejected",
                        step.getName() + " rejected by " + actor.name() + (note == null ? "" : ": " + note), Json.map("by", actor.name()));
                m.add(Mirror.Kind.FINISHED, step, step.getError());
            }
            audit.record(actor, approve ? "workflow_step.approved" : "workflow_step.rejected", "workflow_step", step.getId(),
                    Json.map("runId", run.getId()));
            advance(run, all, m);
        });
    }

    /** An external agent (or an admin on its behalf) reports the result of an externally executed step. */
    public void completeExternal(Actor actor, UUID runId, UUID stepRunId, Map<String, Object> output, String error, boolean retryable) {
        operate(actor, runId, (run, all, m) -> {
            StepRun step = all.stream().filter(s -> s.getId().equals(stepRunId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Step", stepRunId));
            AgentSnapshot agent = agents(run).get(step.getAgentKey());
            boolean isAgent = actor.isAgent() && actor.agentId().equals(agent.agentId());
            if (!isAgent && !(actor.isUser() && actor.isAdmin())) {
                throw ApiException.forbidden("Only " + agent.agentName() + " (with its API key) or an admin can report this step");
            }
            if (!"external".equals(step.getExecutor())) {
                throw ApiException.conflict("not_external", "Step " + step.getStepKey() + " is executed by Memora, not externally", Map.of());
            }
            if (step.getStatus() != StepStatus.RUNNING) {
                throw ApiException.conflict("invalid_step_state", "Step is " + step.getStatus() + ", not RUNNING", Map.of());
            }
            StepOutcome outcome = error != null && !error.isBlank()
                    ? StepOutcome.failed(retryable ? StepOutcome.FailureKind.RETRYABLE : StepOutcome.FailureKind.PERMANENT, error, List.of())
                    : StepOutcome.succeeded(output == null ? Map.of() : output, List.of("Reported by " + actor.name()));
            events.append(run.getTenantId(), run.getId(), step.getId(), "external_result", step.getName() + " result reported by "
                    + actor.name(), Json.map("by", actor.name(), "succeeded", error == null || error.isBlank()));
            record(run, step, outcome, m);
        });
    }

    /** Called when the handoff of an externally executed step is completed, failed or rejected through the handoff API. */
    public void onHandoffFinished(UUID tenantId, UUID handoffId, String status, Map<String, Object> result, String reason) {
        Mirror m = new Mirror();
        tx.executeWithoutResult(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, run_id FROM workflow_step_runs WHERE tenant_id = ? AND handoff_id = ?", tenantId, handoffId);
            if (rows.isEmpty()) {
                return;
            }
            WorkflowRun run = runs.lock(tenantId, (UUID) rows.get(0).get("run_id")).orElseThrow();
            StepRun step = steps.findByTenantIdAndId(tenantId, (UUID) rows.get(0).get("id")).orElseThrow();
            if (!"external".equals(step.getExecutor()) || step.getStatus() != StepStatus.RUNNING) {
                return;
            }
            StepOutcome outcome;
            if ("COMPLETED".equals(status)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> output = result != null && result.get("output") instanceof Map<?, ?> o
                        ? (Map<String, Object>) o : result == null ? Map.of() : result;
                outcome = StepOutcome.succeeded(output, List.of("Completed through handoff " + handoffId));
            } else {
                outcome = StepOutcome.failed("FAILED".equals(status) ? StepOutcome.FailureKind.RETRYABLE : StepOutcome.FailureKind.PERMANENT,
                        "Handoff " + status.toLowerCase() + (reason == null ? "" : ": " + reason), List.of());
            }
            events.append(tenantId, run.getId(), step.getId(), "external_result",
                    step.getName() + " reported through its handoff (" + status + ")", Json.map("handoffId", handoffId.toString()));
            record(run, step, outcome, m);
        });
        mirror.apply(m);
    }

    @FunctionalInterface
    private interface Operation {
        void apply(WorkflowRun run, List<StepRun> all, Mirror m);
    }

    private void operate(Actor actor, UUID runId, Operation op) {
        Mirror m = new Mirror();
        tx.executeWithoutResult(s -> {
            WorkflowRun run = runs.lock(actor.tenantId(), runId).orElseThrow(() -> ApiException.notFound("Workflow run", runId));
            List<StepRun> all = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(actor.tenantId(), runId);
            op.apply(run, all, m);
        });
        mirror.apply(m);
    }

    // ------------------------------------------------------------------ helpers

    private UUID runIdOf(UUID tenantId, UUID stepRunId) {
        List<UUID> ids = jdbc.queryForList("SELECT run_id FROM workflow_step_runs WHERE tenant_id = ? AND id = ?",
                UUID.class, tenantId, stepRunId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    static Map<String, StepRun> latest(Collection<StepRun> all) {
        Map<String, StepRun> out = new LinkedHashMap<>();
        all.stream().sorted(Comparator.comparing(StepRun::getCreatedAt).thenComparing(StepRun::getIteration))
                .forEach(s -> out.merge(s.getStepKey(), s, (a, b) -> b.getIteration() >= a.getIteration() ? b : a));
        return out;
    }

    private static StepRun resolveDep(List<StepRun> all, Map<String, StepRun> latest, String dep) {
        int hash = dep.indexOf('#');
        if (hash < 0) {
            return latest.get(dep);
        }
        String key = dep.substring(0, hash);
        int iteration = Integer.parseInt(dep.substring(hash + 1));
        return all.stream().filter(s -> s.getStepKey().equals(key) && s.getIteration() == iteration).findFirst().orElse(null);
    }

    /** The template view of a run: {@code run.*} and {@code steps.<key>.{output,status,iteration,attempts}}. */
    Map<String, Object> view(WorkflowRun run, Collection<StepRun> all) {
        Map<String, Object> view = new HashMap<>();
        view.put("run", Json.map("id", run.getId().toString(), "goal", run.getGoal(), "name", run.getName(),
                "projectId", run.getProjectId().toString(), "workflowId", run.getWorkflowId().toString(), "inputs", run.getInputs()));
        Map<String, Object> stepsView = new LinkedHashMap<>();
        Map<String, StepRun> latest = latest(all);
        for (Map.Entry<String, StepRun> e : latest.entrySet()) {
            StepRun lastSucceeded = all.stream().filter(s -> s.getStepKey().equals(e.getKey()) && s.getStatus() == StepStatus.SUCCEEDED)
                    .max(Comparator.comparingInt(StepRun::getIteration)).orElse(null);
            Map<String, Object> sv = new HashMap<>();
            sv.put("output", lastSucceeded == null ? null : lastSucceeded.getOutput());
            sv.put("status", e.getValue().getStatus().name());
            sv.put("iteration", e.getValue().getIteration());
            sv.put("attempts", e.getValue().getAttempts());
            stepsView.put(e.getKey(), sv);
        }
        view.put("steps", stepsView);
        return view;
    }

    WorkflowSpec spec(WorkflowRun run) {
        return objectMapper.convertValue(run.getSpec(), WorkflowSpec.class);
    }

    Map<String, AgentSnapshot> agents(WorkflowRun run) {
        Map<String, AgentSnapshot> out = new LinkedHashMap<>();
        run.getAgents().forEach((k, v) -> out.put(k, objectMapper.convertValue(v, AgentSnapshot.class)));
        return out;
    }

    private static String abbreviate(String s, int max) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
