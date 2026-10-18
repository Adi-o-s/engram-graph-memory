# Orchestration architecture (Memora 2.0)

Memora 2.0 turns the memory and coordination platform into a general-purpose orchestrator. The core is one
generic engine that runs **user-defined agents** in **user-defined workflows**. Software delivery, research
reports and the old Planner → Developer → Tester flow are all just configurations of that engine.

```
 Goal ──▶ Planner (rule-based) ──▶ editable proposal ──▶ Workflow definition (versioned spec)
                                                               │ start run (pins spec + agent versions)
                                                               ▼
 ┌────────────────────────────── WorkflowEngine (PostgreSQL is the source of truth) ─────────────────────────────┐
 │ advance(): dependencies satisfied? condition true? approval granted? ──▶ step READY  (run row locked)          │
 │ WorkflowScheduler: SELECT … FOR UPDATE SKIP LOCKED ──▶ RUNNING with lease ──▶ worker pool (parallel)            │
 │ worker: AgentExecutor.execute(ctx) ──▶ ToolGateway (authorise, validate, audit) ──▶ tools                      │
 │ complete(): fenced by lease owner + attempt ──▶ SUCCEEDED / retry with backoff / FAILED / next loop iteration │
 │ completion criteria evaluated on actual outputs ──▶ run SUCCEEDED or FAILED                                    │
 └───────────────────────────────────────────────────────────────────────────────────────────────────────────────┘
        │ after commit (CoordinationMirror)                         │ tools
        ▼                                                           ▼
  tasks + handoffs on the existing board               memories (scoped), artifacts (versioned, SHA-256), tasks
```

## Packages (`backend/src/main/java/com/memora/orchestration`)

| Package | Responsibility |
|---|---|
| `agentdef` | `AgentDefinition` (+ immutable versions), validation, service, REST, system templates and catalogs |
| `executor` | `AgentExecutor` interface, `TemplateExecutor` (deterministic), `ExternalExecutor` (handoff to an outside agent), registry |
| `tool` | `Tool`, `ToolDescriptor`, `ToolRegistry`, `ToolGateway` (authorisation + audit), 21 built-in tools |
| `workflow` | `WorkflowSpec`, `WorkflowValidator` (graph, references, loops, approvals), definitions with versions, REST |
| `run` | `WorkflowEngine`, `WorkflowScheduler`, `CoordinationMirror`, runs, step runs, artifacts, events, approvals |
| `planning` | `WorkflowPlanner` interface, `RuleBasedWorkflowPlanner`, `RequirementExtractor` |
| `expr` | `${…}` templates, structured conditions, a small JSON-schema validator |
| `install` | Default workflows and the idempotent per-tenant installer |

## Data model (migration `V4__orchestration.sql`)

`agent_definitions`, `agent_definition_versions`, `workflow_definitions`, `workflow_definition_versions`,
`workflow_runs`, `workflow_step_runs`, `workflow_artifacts`, `workflow_events`, `workflow_approvals`,
`tool_invocations`; `memories` gains `workflow_id` and `workflow_run_id`. All tables carry `tenant_id`, reference
other rows through `(tenant_id, id)` composite keys, and are covered by `FORCE ROW LEVEL SECURITY`.

Existing concepts are reused instead of duplicated:

* an agent definition links to an `agents` row (its identity: API key, inbox, handoff counters);
* each run has an umbrella **task** and each step a task assigned to its agent, moved through the normal
  transition table (TODO → IN_PROGRESS → DONE / FAILED / BLOCKED);
* each dispatched step gets a real **handoff** from the upstream step's agent (or the per-tenant "Workflow
  Engine" principal), acknowledged, completed or failed as the step progresses;
* step outcomes, decisions and lessons are ordinary **memories** with provenance.

## Step lifecycle

`PENDING → READY → RUNNING → SUCCEEDED`, with `WAITING_APPROVAL`, `BLOCKED` (an upstream step failed),
`SKIPPED` (condition false), `FAILED` and `CANCELLED`. A dependency is satisfied by `SUCCEEDED` or `SKIPPED`.
Each loop iteration is its own step row (`iteration` 1, 2, …), so history is never overwritten.

Run status: `RUNNING`, `PAUSED`, `WAITING_APPROVAL`, `BLOCKED`, `SUCCEEDED`, `FAILED`, `CANCELLED`. When every step
is finished the engine evaluates required steps, mandatory steps and the completion criteria **against the
actual outputs**. A run whose steps all succeeded but whose criteria fail is `FAILED`.

## Execution guarantees (stated precisely)

| Concern | Mechanism | Guarantee |
|---|---|---|
| Two workers run the same step | `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` moves READY → RUNNING with a lease owner and increments `attempts` in one statement | at most one claim per attempt (tested with 8 concurrent claimers racing the scheduler) |
| A slow or dead worker reports late | `complete()` accepts a result only if the step is still RUNNING with the same lease owner **and** attempt number | late or forged results are ignored and logged (`stale_completion_ignored`) |
| Two finishing steps both schedule a successor | every decision runs under a row lock on the run | successors are decided once |
| Worker crash, hang or restart | lease expiry (and, on single-node startup, any step claimed by a previous process) is treated as a retryable failure | bounded retries, then FAILED with the reason |
| Long transactions | executors run between short transactions; nothing holds a transaction while a step executes | |
| Duplicate side effects | delivery is **at-least-once per attempt**. Built-in tools are idempotent or versioned: memory writes deduplicate, artifacts add versions, `task.create` uses an idempotency key | external tools must be idempotent too |
| Board/handoff updates conflict with a person editing a task | applied after commit in separate transactions and retried; failures become `coordination_warning` events | a board conflict can never undo a step's result |
| Restart | all state is in PostgreSQL; completed steps keep their outputs and are never re-run | tested by stopping one backend and starting another |

Retries use exponential backoff (`backoffSeconds × 2^(attempt−1)`, capped at 300 s). Permanent failures (rule
violations, denied tools, invalid output) are not retried. A person can grant one more attempt to a failed step.

## Runtime intelligence boundary

| Layer | What it is in this build |
|---|---|
| Configured agent role | Text. It never changes behaviour. |
| Workflow planning | Rule-based keyword matching and templates (`rule-based-v1`); every proposal is shown for review. |
| Deterministic task execution | `TemplateExecutor`: configured tool operations; same inputs and memory give the same outputs. |
| Tool execution | Real: memory reads/writes, artifacts with checksums, checks over actual content, CSV maths, tasks, optional workspace files and allow-listed commands. |
| External execution | `ExternalExecutor`: a person, script or AI coding agent with the agent's API key does the work and reports through the handoff API. |
| Model-powered reasoning | **Not included.** `AgentExecutor` and `WorkflowPlanner` are the extension points; a model executor could be added without changing the engine. Validation rejects `executor: model` today. |

The built-in software-delivery agents therefore produce requirements, contracts, a design specification, a SQL
schema scaffold, implementation plans, test plans and verification reports. They do not write, compile or deploy
application code, and the final report says so.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `memora.workflow.workers` (`MEMORA_WORKFLOW_WORKERS`) | 4 | parallel step workers per backend |
| `memora.workflow.poll-interval-ms` | 400 | scheduler poll interval (also woken when a step finishes) |
| `memora.workflow.recover-on-startup` | true | requeue steps left RUNNING by a previous process; set false with several backends |
| `memora.workflow.max-pacing-ms` | 5000 | upper bound for the demo pacing option |
| `MEMORA_WORKSPACE_ROOT` | empty | directory the `workspace.*` tools may read; empty disables them |
| `memora.workspace.commands.<id>` | none | allow-listed argument vectors for `command.run` |

## API

| Resource | Endpoints |
|---|---|
| Agent definitions | `GET/POST /api/v1/agent-definitions`, `GET/PUT …/{id}`, `POST …/{id}/duplicate`, `…/activate`, `…/deactivate`, `GET …/{id}/versions`, `POST /api/v1/agent-definitions/validate` |
| Templates, tools, executors | `GET /api/v1/agent-templates`, `POST /api/v1/agent-templates/{key}/install`, `GET /api/v1/tools`, `GET /api/v1/executors` |
| Workflows | `GET/POST /api/v1/workflows`, `GET/PUT …/{id}`, `POST /api/v1/workflows/validate`, `POST …/{id}/duplicate`, `…/archive`, `…/restore`, `GET …/{id}/versions`, `POST …/{id}/runs` |
| Planning | `POST /api/v1/workflow-plans`, `POST /api/v1/workflow-plans/accept` |
| Runs | `GET /api/v1/workflow-runs`, `GET …/{id}`, `…/events`, `…/artifacts`, `…/tool-calls`, `GET …/{id}/steps/{stepId}`, `POST …/{id}/pause`, `…/resume`, `…/cancel`, `POST …/steps/{stepId}/retry`, `…/approve`, `…/reject`, `…/complete` |
| Artifacts, workspace | `GET /api/v1/workflow-artifacts/{id}`, `GET /api/v1/projects/{id}/workspace` |

Every endpoint takes the tenant from authentication; another tenant's ids return 404. Creating and editing agents
and workflows, approving steps and injecting faults require the tenant `ADMIN` role; members can view, plan and
run. The full contract is in Swagger UI (`/swagger-ui.html`).
