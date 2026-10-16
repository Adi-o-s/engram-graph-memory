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

## Next (not started)
- [ ] Tenant/user administration API (sign-up, invitations, password reset)
- [ ] First full `docker compose up --build` on a machine with normal internet access
- [ ] Optional local embedding extractor behind `MemoryExtractor` (must stay zero-cost)
- [ ] API rate limiting and JWT revocation
- [ ] Code-split the dashboard bundle
