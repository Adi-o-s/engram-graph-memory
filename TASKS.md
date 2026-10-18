# Memora: Task Tracker

Status legend: [x] done, [~] in progress, [ ] not started.

## Phase A: Inspect and plan
- [x] Inspect repository (empty) and toolchain
- [x] IMPLEMENTATION_PLAN.md
- [x] TASKS.md

## Phase B: Vertical slice
- [x] Maven project, Flyway schema, local PostgreSQL
- [x] Conversation + memory create/retrieve, dashboard

## Phase C: Core platform
- [x] Memory types, rule-based extraction, supersession with history, search, context builder
- [x] Tasks (versioned), handoffs, inboxes, leases and recovery, dead letter
- [x] Auth (JWT + agent keys), tenant isolation, row-level security
- [x] Concurrency tests (optimistic locking, gapless sequences, competing facts, idempotency)

## Phase D: Demo
- [x] Dashboard: overview, guided demo, memory explorer, conversations, agents + context builder,
      task/handoff board, tenant isolation, concurrency lab
- [x] Seed data for two tenants
- [x] Concurrency lab and HTTP load test with measured results

## Phase E: Verification and handover
- [x] Backend tests (93), dashboard unit tests (11), browser tests (8, local and Docker stack)
- [x] Docker Compose stack verified (runtime images; build stages not runnable in the build sandbox)
- [x] Docs: README, SETUP_GUIDE, ARCHITECTURE, DEMO_SCRIPT, REQUIREMENTS_FROM_USER, TEST_RESULTS
- [x] Cost and dependency audit
- [~] Pull request reviewed and merged

## Memora 2.0: orchestration platform

### Phase A: audit
- [x] Repository, schema, APIs, demo agents, tests inspected; baseline 93/93 recorded
- [x] docs/ORCHESTRATION_AUDIT.md

### Phase B: agent definitions
- [x] Persisted, versioned agent definitions linked to agent principals (V4 migration)
- [x] Validation (tools, capabilities, executors, schemas, memory policy, instruction warnings)
- [x] Generic `AgentExecutor`; deterministic `template` and `external` executors; `model` documented, not shipped
- [x] Planner, Developer, Tester as templates linked to the seeded principals; 21 more templates

### Phase C: workflows and engine
- [x] Versioned workflow specs; graph validation (cycles, references, loops, approvals, completion)
- [x] Database-backed scheduler (SKIP LOCKED claims, leases, fenced completion), parallel workers
- [x] Conditions, bounded loops with remediation, retries with backoff, approvals, pause/resume/cancel/retry

### Phase D: tools and recovery
- [x] Tool registry and gateway (authorisation, schema validation, audit, redaction); 21 tools
- [x] Workspace and command tools confined and off by default
- [x] Lease recovery and startup recovery; tasks and handoffs mirrored after commit

### Phase E: dashboard
- [x] Plan a workflow, Workflow runs (live graph, timeline, inspector), Workflow studio, Agent studio, Project workspace

### Phase F: memory and artifacts
- [x] RUN, WORKFLOW and AGENT memory scopes; policy-restricted retrieval; provenance; artifacts with checksums

### Phase G: tests
- [x] 41 new backend tests (134 total), 4 new Vitest tests (15), 3 new Playwright tests (11); all passing

### Phase H: demo and docs
- [x] Software-delivery and research workflows on the same engine; restart verified on the Docker stack
- [x] ORCHESTRATION_ARCHITECTURE, AGENT_DEFINITIONS, WORKFLOW_BUILDER, TOOL_SECURITY, ORCHESTRATION_DEMO, README, TEST_RESULTS, cost audit
- [ ] Push the branch and open a pull request (needs GitHub credentials on the development machine)

### Remaining ideas
- [ ] A model-backed `AgentExecutor` / `WorkflowPlanner` as an optional plug-in (would need a model; not zero-cost by default)
- [ ] Multi-instance deployment test (several backends on one database)
- [ ] Visual drag-and-drop graph editing (the form editor is the accessible primary path)
- [ ] Load test of the workflow engine

## Next (not started)
- [ ] Tenant/user administration API (sign-up, invitations, password reset)
- [ ] First full `docker compose up --build` on a machine with normal internet access
- [ ] Optional local embedding extractor behind `MemoryExtractor` (must stay zero-cost)
- [ ] API rate limiting and JWT revocation
- [ ] Code-split the dashboard bundle
