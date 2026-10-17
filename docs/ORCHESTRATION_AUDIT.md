# Orchestration audit (Memora 1.0 → 2.0)

This audit was written by reading the code on branch `claude/memora-platform-lxinhn` (commit `45de2b7`) before any
orchestration code was added. Every statement about current behaviour points at the class that implements it.
Nothing here is taken from earlier documentation without checking the code.

## 1. Baseline

| Check | Result |
|---|---|
| Git status | clean except an untracked `.gitattributes` (added locally so `mvnw` and `*.sh` keep LF line endings on Windows; without it `docker compose up --build` fails with `./mvnw: not found`) |
| Backend tests | **93 passed, 0 failed, 0 skipped** (`./mvnw test`, 15 classes) |
| Running stack | `docker compose up` healthy; dashboard, API health and login verified |

The baseline was run in an `eclipse-temurin:21-jdk` container against a dedicated, throwaway `postgres:16-alpine`
container with a non-superuser role, so the running stack's database was never touched.

## 2. What exists today (verified in code)

| # | Question | Finding | Where |
|---|---|---|---|
| 1 | How are agents represented? | A row in `agents`: name, a role from a fixed enum, free-text capability labels, a hashed API key and handoff counters. It is an **identity** (a principal that can authenticate), not a behaviour. | `agent/Agent.java`, `V1__core_schema.sql` (`role CHECK IN ('PLANNER','DEVELOPER','TESTER','CUSTOM')`) |
| 2 | Hardcoded or dynamic? | Registration is dynamic, but **behaviour is hardcoded**: the only agents that do anything inside the platform are three Java methods, `DemoAgents.plan/develop/test`. A `CUSTOM` agent can only act from outside, through the REST API with its key. | `demo/DemoAgents.java` |
| 3 | Are agent definitions persisted? | Only the identity. There are no instructions, input/output schema, tool permissions, memory policy, timeout or retry settings anywhere. Capabilities are labels and are never checked. | `agent/AgentService.java` |
| 4 | Arbitrary task types and dependencies? | Tasks are generic (title, description, JSONB `state`, versioned) but have **no type, no dependencies and no parent/child links**. The status set is fixed: `TODO, IN_PROGRESS, BLOCKED, IN_REVIEW, DONE, FAILED`. | `task/Task.java`, `task/TaskStatus.java` |
| 5 | Workflows? | **None.** No table, entity or API for a workflow definition, version or execution. | — |
| 6 | Coordinator | A fixed three-step sequence driven by four HTTP endpoints. `DemoService` picks agents by role (`requireByRole(PLANNER)`), and `nextStep()` infers progress from the number of handoffs (`hs.size() >= 2` means "Tester"). | `demo/DemoService.java` |
| 7 | Handoffs | Solid and reusable: structured payload, memory references checked for tenant and visibility, `PENDING → ACKNOWLEDGED → COMPLETED`, `FAILED → retry`, `REJECTED`, `DEAD_LETTER` after `maxAttempts`, a lease on acknowledge, a scheduled lease-recovery job, row locks on every transition, and a partial unique index allowing **one active handoff per task**. | `handoff/HandoffService.java`, `HandoffLeaseRecovery.java` |
| 8 | Memory scopes | Scope key is `tenant`, `project:<id>` or `user:<id>`. Memories can also carry `conversation_id`, `task_id`, `agent_id` as filters. There is **no workflow, run or agent scope** and no policy saying which agent may read which scope. | `memory/MemoryService.java#scopeKey`, `MemorySearchService` |
| 9 | Context across sessions | Yes, for the three existing scopes: `ContextService` builds a bounded context (facts, procedures, episodes, recent messages) within a character budget, from whatever is in PostgreSQL. | `context/ContextService.java` |
| 10 | Concurrency on task state | `@Version` plus a mandatory `expectedVersion` (409 `stale_version`), transition table, append-only `task_events`. Facts use an advisory lock + partial unique index. | `task/TaskService.java#update`, `MemoryService#createInTransaction` |
| 11 | Dashboard uses real APIs? | Yes. `frontend/src/api/endpoints.ts` is the only data source; there is no mock data. | `frontend/src/api/endpoints.ts` |
| 12 | Simulated / hardcoded / incomplete | The Developer's "implementation" is a JSON description written to task state; no code is produced. The Tester checks consistency of those records. The first Tester failure is simulated (`failFirst=true`). The demo task, requirements and messages are constants. Capability labels are decorative. | `DemoAgents.java` |
| 13 | Can agents invoke tools? | **No tool concept exists.** In-platform agents call Java services directly; external agents call the REST API. Nothing restricts *which* operations an agent may perform beyond its tenant and the handoff receiver checks. | — |
| 14 | Long-running work across restarts | There is no long-running work: each demo step is one synchronous HTTP request. All state is in PostgreSQL, so a restart loses nothing, but nothing resumes by itself either (only handoff leases are recovered). | `PersistenceRestartIT`, `HandoffLeaseRecovery` |
| 15 | Auth and tenant isolation for new resources | Every service takes an `Actor` whose tenant comes from authentication. `FORCE ROW LEVEL SECURITY` covers 11 tables through `app.tenant_id`, set on each connection checkout by `TenantAwareDataSource`. Background jobs run in an explicit system scope. **Any new table must be added to RLS and every new repository query must filter by tenant.** | `security/*`, `V2__row_level_security.sql` |

## 3. Limitations that block a general-purpose orchestrator

1. Behaviour lives in Java methods named after roles. Adding a "Researcher" means writing a new class.
2. No dependency model, so no parallelism, no "wait for prerequisites", no conditions, no bounded loops.
3. No durable execution record per step (attempts, inputs, outputs, errors, why it became ready).
4. No tool layer, so there is nothing to authorize, validate or audit at the level of "what an agent may do".
5. Memory scopes stop at project level; agents cannot keep run-local working memory or reusable per-workflow lessons.
6. The fixed `agents.role` enum and the `requireByRole` lookup tie coordination to three role names.
7. Progress happens only when someone calls an HTTP endpoint; there is no scheduler.

## 4. What is reused unchanged

| Component | Reuse |
|---|---|
| `Actor`, `TenantContext`, `TenantAwareDataSource`, RLS functions | Every new table gets the same `tenant_isolation` policy; the scheduler claims work in system scope and executes each step in the step's tenant scope. |
| `agents` table | Stays the **identity** of an agent. Each agent definition links to one principal, so handoffs, inboxes, counters, API keys and the task board keep working. |
| `tasks` + `task_events` | Each run gets an umbrella task and each step a task assigned to its agent, so the existing board shows workflow work. |
| `handoffs` | The engine creates a real handoff for every step it dispatches (from the upstream step's agent, or from the "Workflow Engine" principal for root steps) and acknowledges, completes or fails it as the step progresses. External agents can complete a step through the existing handoff API. |
| `memories`, `MemorySearchService`, `ContextService` | Extended with new scopes; supersession, provenance and history are unchanged. |
| `audit_events`, `Idempotency`, `ApiException`, problem details | Used as-is. |

## 5. What is refactored

| Component | Change |
|---|---|
| Planner / Developer / Tester | Become **agent definitions** created from templates. The original guided demo (`/api/v1/demo/*`) is kept for backward compatibility and still passes its tests. |
| `MemoryScope` | Adds `WORKFLOW`, `RUN` and `AGENT`. Existing keys (`tenant`, `project:`, `user:`) are unchanged. |
| `MemorySearchService` / `MemoryQuery` | Adds `runId` and `scopeKeys` filters so the engine can enforce an agent's memory policy in SQL. |
| `Agent` | Gains a rename setter (definition renames propagate to the principal). |
| `HandoffService` | Publishes an application event when a handoff is completed, failed or rejected, so an externally executed step can finish through the existing handoff API. |

## 6. Proposed data model (migration `V4__orchestration.sql`)

All tables carry `tenant_id`, use composite foreign keys `(tenant_id, id)` and get `FORCE ROW LEVEL SECURITY`.

| Table | Purpose |
|---|---|
| `agent_definitions` | Configurable agent: key, name, description, role (free text), instructions, capabilities, permitted tools, input/output JSON schema, memory policy, context policy, executor, behaviour (executor configuration), timeout, max attempts, backoff, status, version, linked principal. |
| `agent_definition_versions` | Immutable snapshot per saved version. Runs pin the snapshot they started with. |
| `workflow_definitions` | Name, key, description, project, current version, status. |
| `workflow_definition_versions` | Immutable, validated JSON spec per version: inputs, steps, dependencies, mappings, conditions, retry/timeout, loops, approvals, completion criteria. |
| `workflow_runs` | One execution: pinned spec and agent snapshots, goal, inputs, status, completion report, umbrella task, pacing and fault-injection options, lock version. |
| `workflow_step_runs` | One row per step **iteration**: status, attempts, input, output, error, lease owner and expiry, next attempt time, why it became ready, linked task and handoff. Unique `(run, step, iteration)`. |
| `workflow_artifacts` | Inspectable outputs (Markdown, JSON) with SHA-256 checksum, linked to run and step. |
| `workflow_events` | Append-only execution history. |
| `workflow_approvals` | Human approval gates. |
| `tool_invocations` | One audited row per tool call: tool, redacted input summary, outcome, duration. |
| `memories` (altered) | Adds nullable `workflow_id`, `workflow_run_id` with tenant-composite foreign keys and an index. |

Why the definition is a JSON spec and executions are normalized: a workflow is edited, validated and versioned as a
whole, so an immutable validated document per version is the natural unit. Executions are queried, claimed and
updated row by row, so they are normalized tables with indexes for the scheduler.

## 7. Proposed API (all under `/api/v1`, OpenAPI documented)

| Resource | Endpoints |
|---|---|
| Agent definitions | `GET/POST /agent-definitions`, `GET/PUT /agent-definitions/{id}`, `POST …/{id}/duplicate`, `POST …/{id}/activate`, `POST …/{id}/deactivate`, `POST /agent-definitions/validate`, `GET …/{id}/versions`, `GET /agent-templates`, `POST /agent-templates/{key}/install` |
| Tools | `GET /tools`, `GET /executors` |
| Workflows | `GET/POST /workflows`, `GET/PUT /workflows/{id}`, `POST /workflows/validate`, `POST /workflows/{id}/duplicate`, `GET /workflows/{id}/versions`, `POST /workflows/{id}/runs` |
| Planning | `POST /workflow-plans` (proposal, nothing persisted), `POST /workflow-plans/accept` (installs missing agents and saves the edited workflow) |
| Runs | `GET /workflow-runs`, `GET /workflow-runs/{id}`, `POST …/{id}/pause`, `…/resume`, `…/cancel`, `POST …/{id}/steps/{stepRunId}/retry`, `…/approve`, `…/reject`, `…/complete` (external executor), `GET …/{id}/events`, `GET …/{id}/artifacts`, `GET /workflow-artifacts/{id}`, `GET …/{id}/context/{stepRunId}` |

## 8. Security and concurrency implications

* **Tenant isolation**: new tables are RLS-forced; every repository method takes the tenant id; cross-tenant ids return 404.
* **Agents cannot widen their permissions**: tools are authorized against the *pinned* agent snapshot; the executor never receives a repository or service, only a gateway that checks the tool is in the definition's `tools` list, that the agent has the tool's required capability, and that the tool's arguments stay inside the run's scope (project, run, workspace).
* **Instructions are data**: agent instructions are stored and shown, never interpreted as permissions. Validation rejects tools that do not exist and capabilities a tool requires but the agent does not declare.
* **Filesystem / commands**: workspace tools are confined to a configured directory (canonical path check, no symlink escape, size limits). The command tool runs only allow-listed command ids mapped to fixed argument vectors, without a shell, and requires an approval gate. Both are disabled unless a workspace is configured.
* **Secrets**: tool inputs and outputs go through the existing `SensitiveContentGuard` before they are stored in memory, events or tool logs.
* **Duplicate execution**: steps are claimed with `SELECT … FOR UPDATE SKIP LOCKED` and moved to `RUNNING` with a lease owner in the same transaction. Completion is fenced: it is accepted only if the step is still `RUNNING` with the same lease owner and attempt number.
* **Run state**: advancing a run (computing ready steps, conditions, loops, final status) takes a row lock on the run, so two finishing steps cannot both decide the next step.
* **No long transactions**: claiming, completing and advancing are short transactions; executors run between them.

## 9. Migration and backward compatibility

* New migration `V4__orchestration.sql`; V1–V3 are untouched.
* Existing endpoints keep their contracts. The guided demo still works exactly as before.
* Existing databases (like the running stack) receive default agent definitions and two example workflows per
  tenant through an idempotent startup installer; existing memories, tasks and handoffs are not modified.
* No data is reset or deleted.

## 10. Phases and acceptance criteria

| Phase | Deliverable | Accepted when |
|---|---|---|
| B | Agent definitions, versions, validation, templates, generic `AgentExecutor`, Planner/Developer/Tester as templates | CRUD/duplicate/activate tests pass; two different definitions run through the same executor interface |
| C | Workflow specs, validator, runs, step runs, database-backed scheduler | cycle and reference validation tests; sequential, parallel and conditional runs pass; dependent steps wait |
| D | Tool registry and gateway, authorization, retries with backoff, leases, recovery, approvals, cancel/pause/resume | unauthorized tool calls rejected; bounded retry; duplicate claims impossible; resume after simulated crash |
| E | Agent Studio, Workflow Studio, Workflow Runs, Project workspace | UI drives every operation through real APIs; Playwright covers create agent → workflow → run → inspect → retry |
| F | Memory scopes `WORKFLOW`, `RUN`, `AGENT`; memory policy; artifacts in context | scoped retrieval tests including cross-run and cross-tenant |
| G | Security, concurrency, recovery and end-to-end tests | all new and existing tests pass |
| H | Two demos (software delivery, research report) on the same engine; documentation; cost audit | both demos complete through the engine with no special-case code |
