package com.memora.orchestration.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memora.common.ApiException;
import com.memora.common.Json;
import com.memora.common.PageResponse;
import com.memora.memory.MemoryDtos.MemoryView;
import com.memora.memory.MemoryQuery;
import com.memora.memory.MemorySearchService;
import com.memora.memory.MemoryType;
import com.memora.orchestration.agentdef.AgentSnapshot;
import com.memora.orchestration.run.ArtifactService.ArtifactView;
import com.memora.orchestration.run.EventLog.EventView;
import com.memora.orchestration.run.RunDtos.ApprovalView;
import com.memora.orchestration.run.RunDtos.ProjectWorkspace;
import com.memora.orchestration.run.RunDtos.RunSummary;
import com.memora.orchestration.run.RunDtos.RunView;
import com.memora.orchestration.run.RunDtos.StepDetail;
import com.memora.orchestration.run.RunDtos.StepView;
import com.memora.orchestration.run.RunDtos.ToolCallView;
import com.memora.orchestration.workflow.WorkflowService;
import com.memora.orchestration.workflow.WorkflowSpec;
import com.memora.orchestration.workflow.WorkflowValidator;
import com.memora.project.ProjectService;
import com.memora.project.ProjectService.ProjectView;
import com.memora.security.Actor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of workflow runs: run views, step details, events, tool calls, artifacts and project workspaces. */
@Service
public class RunService {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final WorkflowRunRepository runs;
    private final StepRunRepository steps;
    private final ApprovalRepository approvals;
    private final ArtifactService artifacts;
    private final EventLog events;
    private final WorkflowService workflows;
    private final WorkflowValidator validator;
    private final ProjectService projects;
    private final MemorySearchService search;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public RunService(WorkflowRunRepository runs, StepRunRepository steps, ApprovalRepository approvals, ArtifactService artifacts,
                      EventLog events, WorkflowService workflows, WorkflowValidator validator, ProjectService projects,
                      MemorySearchService search, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.runs = runs;
        this.steps = steps;
        this.approvals = approvals;
        this.artifacts = artifacts;
        this.events = events;
        this.workflows = workflows;
        this.validator = validator;
        this.projects = projects;
        this.search = search;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<RunSummary> list(Actor actor, UUID projectId, UUID workflowId, RunStatus status, int page, int size) {
        projects.requireInTenant(actor, projectId);
        return PageResponse.of(runs.search(actor.tenantId(), projectId, workflowId, status, PageRequest.of(page, size)),
                r -> summary(r, steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(actor.tenantId(), r.getId())));
    }

    @Transactional(readOnly = true)
    public RunView get(Actor actor, UUID id) {
        WorkflowRun run = require(actor, id);
        List<StepRun> all = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(actor.tenantId(), id);
        Map<UUID, WorkflowApproval> approvalsByStep = approvals.findByTenantIdAndRunIdOrderByCreatedAtAsc(actor.tenantId(), id).stream()
                .collect(Collectors.toMap(WorkflowApproval::getStepRunId, a -> a, (a, b) -> b));
        Map<String, AgentSnapshot> agents = agents(run);
        Map<String, StepRun> latest = WorkflowEngine.latest(all);
        WorkflowSpec spec = objectMapper.convertValue(run.getSpec(), WorkflowSpec.class);
        List<StepView> stepViews = all.stream().map(s -> stepView(s, agents, spec, approvalsByStep.get(s.getId()),
                latest.get(s.getStepKey()) == s, run)).toList();
        WorkflowValidator.Result analysis = validator.validate(run.getSpec(), agents, statuses(agents), false);
        String workflowKey = jdbc.queryForList("SELECT wf_key FROM workflow_definitions WHERE tenant_id = ? AND id = ?",
                String.class, actor.tenantId(), run.getWorkflowId()).stream().findFirst().orElse(null);
        List<Map<String, Object>> agentList = agents.values().stream().map(a -> Json.map("key", a.key(), "name", a.name(),
                "role", a.role(), "version", a.version(), "executor", a.executor(), "agentId", a.agentId().toString(),
                "tools", a.tools())).toList();
        return new RunView(run.getId(), run.getName(), run.getGoal(), run.getStatus(), run.getWorkflowId(), workflowKey,
                run.getWorkflowVersion(), run.getProjectId(), run.getInputs(), run.getOptions(), run.getSpec(), run.getCompletion(),
                run.getOutputs(), run.getTaskId(), run.getStartedByUserId(), stepViews, counts(latest.values()),
                analysis.analysis() == null ? List.of() : analysis.analysis().layers(), agentList, run.getCreatedAt(),
                run.getUpdatedAt(), run.getFinishedAt());
    }

    @Transactional(readOnly = true)
    public StepDetail step(Actor actor, UUID runId, UUID stepRunId) {
        WorkflowRun run = require(actor, runId);
        StepRun step = steps.findByTenantIdAndId(actor.tenantId(), stepRunId)
                .filter(s -> s.getRunId().equals(runId)).orElseThrow(() -> ApiException.notFound("Step", stepRunId));
        List<StepRun> all = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(actor.tenantId(), runId);
        WorkflowSpec spec = objectMapper.convertValue(run.getSpec(), WorkflowSpec.class);
        StepView view = stepView(step, agents(run), spec,
                approvals.findByTenantIdAndStepRunId(actor.tenantId(), stepRunId).orElse(null),
                WorkflowEngine.latest(all).get(step.getStepKey()) == step, run);
        List<EventView> stepEvents = events.list(actor.tenantId(), runId, 2000).stream()
                .filter(e -> stepRunId.equals(e.stepRunId())).toList();
        List<ArtifactView> stepArtifacts = artifacts.allOfRun(actor.tenantId(), runId).stream()
                .filter(a -> stepRunId.equals(a.getStepRunId())).map(a -> ArtifactView.of(a, false, step.getStepKey())).toList();
        return new StepDetail(view, stepEvents, toolCalls(actor, runId, stepRunId), stepArtifacts);
    }

    @Transactional(readOnly = true)
    public List<EventView> events(Actor actor, UUID runId) {
        require(actor, runId);
        return events.list(actor.tenantId(), runId, 2000);
    }

    @Transactional(readOnly = true)
    public List<ArtifactView> artifacts(Actor actor, UUID runId, boolean allVersions) {
        require(actor, runId);
        Map<UUID, String> keys = steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(actor.tenantId(), runId).stream()
                .collect(Collectors.toMap(StepRun::getId, s -> s.getStepKey() + (s.getIteration() > 1 ? "#" + s.getIteration() : "")));
        return (allVersions ? artifacts.allOfRun(actor.tenantId(), runId) : artifacts.latestOfRun(actor.tenantId(), runId)).stream()
                .map(a -> ArtifactView.of(a, false, a.getStepRunId() == null ? null : keys.get(a.getStepRunId()))).toList();
    }

    @Transactional(readOnly = true)
    public ArtifactView artifact(Actor actor, UUID artifactId) {
        WorkflowArtifact a = artifacts.get(actor, artifactId);
        String stepKey = a.getStepRunId() == null ? null : steps.findByTenantIdAndId(actor.tenantId(), a.getStepRunId())
                .map(StepRun::getStepKey).orElse(null);
        return ArtifactView.of(a, true, stepKey);
    }

    @Transactional(readOnly = true)
    public List<ToolCallView> toolCalls(Actor actor, UUID runId, UUID stepRunId) {
        require(actor, runId);
        StringBuilder sql = new StringBuilder("""
                SELECT id, step_run_id, tool_id, status, side_effects, input_summary::text AS input, output_summary::text AS output,
                       error, duration_ms, created_at
                FROM tool_invocations WHERE tenant_id = ? AND run_id = ?""");
        List<Object> args = new ArrayList<>(List.of(actor.tenantId(), runId));
        if (stepRunId != null) {
            sql.append(" AND step_run_id = ?");
            args.add(stepRunId);
        }
        sql.append(" ORDER BY created_at LIMIT 1000");
        return jdbc.query(sql.toString(), (rs, i) -> new ToolCallView(rs.getObject("id", UUID.class),
                rs.getObject("step_run_id", UUID.class), rs.getString("tool_id"), rs.getString("status"),
                rs.getBoolean("side_effects"), parse(rs.getString("input")), parse(rs.getString("output")), rs.getString("error"),
                rs.getInt("duration_ms"), rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    /** The project as a workspace: goal and runs, decisions and requirements in memory, artifacts, outstanding work, history. */
    @Transactional(readOnly = true)
    public ProjectWorkspace workspace(Actor actor, UUID projectId) {
        ProjectView project = projects.get(actor, projectId);
        List<WorkflowRun> projectRuns = runs.findByTenantIdAndProjectIdOrderByCreatedAtDesc(actor.tenantId(), projectId);
        List<RunSummary> summaries = projectRuns.stream().map(r -> summary(r,
                steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(actor.tenantId(), r.getId()))).toList();
        List<Map<String, Object>> decisions = memories(actor, projectId, "decision", MemoryType.SEMANTIC);
        List<Map<String, Object>> requirements = memories(actor, projectId, "requirement", MemoryType.SEMANTIC);
        List<Map<String, Object>> lessons = new ArrayList<>(memories(actor, projectId, "lesson", MemoryType.PROCEDURAL));
        List<ArtifactView> projectArtifacts = artifacts.ofProject(actor, projectId, 200).stream()
                .map(a -> ArtifactView.of(a, false, null)).toList();
        List<Map<String, Object>> outstanding = new ArrayList<>();
        for (WorkflowRun r : projectRuns) {
            if (r.getStatus() == RunStatus.SUCCEEDED || r.getStatus() == RunStatus.CANCELLED) {
                continue;
            }
            for (StepRun s : WorkflowEngine.latest(steps.findByTenantIdAndRunIdOrderByCreatedAtAscIterationAsc(actor.tenantId(), r.getId())).values()) {
                if (!StepStatus.SATISFIED.contains(s.getStatus())) {
                    outstanding.add(Json.map("runId", r.getId().toString(), "run", r.getName(), "stepRunId", s.getId().toString(),
                            "step", s.getName(), "status", s.getStatus().name(), "reason", s.getError()));
                }
            }
        }
        List<Map<String, Object>> history = jdbc.queryForList("""
                SELECT e.event_type AS type, e.message, e.created_at AS "createdAt", e.run_id AS "runId"
                FROM workflow_events e JOIN workflow_runs r ON r.id = e.run_id AND r.tenant_id = e.tenant_id
                WHERE e.tenant_id = ? AND r.project_id = ?
                  AND e.event_type IN ('run_started', 'run_succeeded', 'run_failed', 'run_cancelled', 'run_paused', 'run_resumed',
                                       'step_failed', 'loop_continue', 'approval_granted', 'approval_rejected', 'step_retry_requested',
                                       'lease_expired', 'retry_scheduled')
                ORDER BY e.created_at DESC LIMIT 100
                """, actor.tenantId(), projectId);
        return new ProjectWorkspace(project.id(), project.name(), project.description(), summaries, decisions, requirements,
                lessons, projectArtifacts, outstanding, history);
    }

    private List<Map<String, Object>> memories(Actor actor, UUID projectId, String category, MemoryType type) {
        return search.search(actor, MemoryQuery.builder().project(projectId).includeShared(false).type(type).category(category)
                        .limit(50).sort(MemoryQuery.Sort.RECENT).build()).items().stream()
                .map(RunService::memoryMap).toList();
    }

    private static Map<String, Object> memoryMap(MemoryView m) {
        return Json.map("id", m.id().toString(), "content", m.content(), "scope", m.scope().name(), "status", m.status().name(),
                "version", m.version(), "createdAt", m.createdAt().toString(), "workflowRunId",
                m.workflowRunId() == null ? null : m.workflowRunId().toString(), "agentId", m.agentId() == null ? null : m.agentId().toString());
    }

    private StepView stepView(StepRun s, Map<String, AgentSnapshot> agents, WorkflowSpec spec, WorkflowApproval approval,
                              boolean isLatest, WorkflowRun run) {
        AgentSnapshot agent = agents.get(s.getAgentKey());
        Long duration = s.getStartedAt() != null && s.getFinishedAt() != null
                ? Duration.between(s.getStartedAt(), s.getFinishedAt()).toMillis() : null;
        boolean remediation = spec.step(s.getStepKey()).map(WorkflowSpec.StepSpec::isRemediation).orElse(false);
        ApprovalView av = approval == null ? null : new ApprovalView(approval.getId(), approval.getStatus(), approval.getReason(),
                approval.getDecidedByUserId(), approval.getDecisionNote(), approval.getCreatedAt(), approval.getDecidedAt());
        boolean canRetry = isLatest && s.getStatus() == StepStatus.FAILED && run.getStatus() != RunStatus.CANCELLED;
        return new StepView(s.getId(), s.getStepKey(), s.getIteration(), s.getName(), s.getAgentKey(),
                agent == null ? null : agent.name(), s.getAgentVersion(), s.getExecutor(), s.getStatus(), s.getDependsOn(),
                s.getAttempts(), s.getMaxAttempts(), s.getInput(), s.getOutput(), s.getError(), s.getErrorKind(), s.getReadyReason(),
                s.getLeaseOwner(), s.getLeaseExpiresAt(), s.getNextAttemptAt(), s.getTaskId(), s.getHandoffId(), s.getCreatedAt(),
                s.getReadyAt(), s.getStartedAt(), s.getFinishedAt(), duration, remediation, isLatest, av, canRetry);
    }

    private RunSummary summary(WorkflowRun r, List<StepRun> all) {
        return new RunSummary(r.getId(), r.getName(), r.getGoal(), r.getStatus(), r.getWorkflowId(), r.getWorkflowVersion(),
                r.getProjectId(), counts(WorkflowEngine.latest(all).values()), r.getCreatedAt(), r.getUpdatedAt(), r.getFinishedAt());
    }

    private static Map<String, Long> counts(java.util.Collection<StepRun> latest) {
        Map<String, Long> out = new TreeMap<>();
        latest.forEach(s -> out.merge(s.getStatus().name(), 1L, Long::sum));
        return out;
    }

    private Map<String, AgentSnapshot> agents(WorkflowRun run) {
        Map<String, AgentSnapshot> out = new LinkedHashMap<>();
        run.getAgents().forEach((k, v) -> out.put(k, objectMapper.convertValue(v, AgentSnapshot.class)));
        return out;
    }

    private static Map<String, String> statuses(Map<String, AgentSnapshot> agents) {
        Map<String, String> out = new LinkedHashMap<>();
        agents.keySet().forEach(k -> out.put(k, "ACTIVE"));
        return out;
    }

    WorkflowRun require(Actor actor, UUID id) {
        return runs.findByTenantIdAndId(actor.tenantId(), id).orElseThrow(() -> ApiException.notFound("Workflow run", id));
    }

    private Map<String, Object> parse(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
