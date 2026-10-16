# Memora: Implementation Plan

## Environment found (Phase A, 2026-10-16)

| Tool | Version | Notes |
|---|---|---|
| Java | OpenJDK 21.0.12 | target release 21 |
| Maven | 3.9.11 | Maven Wrapper committed so contributors need only a JDK |
| Node.js / npm | 22.22 / 10.9 | lockfile committed |
| PostgreSQL | 16.15 (local package) | used for tests in the build container |
| Docker | 29.8 | daemon available; Docker Hub pulls were rate limited (HTTP 429) in the build container, so tests also support an existing PostgreSQL via `MEMORA_TEST_DB_URL` |

The repository was empty, so there is no code to retain.

## Architecture

Modular monolith:

```
React + Vite dashboard  --->  Spring Boot REST API (/api/v1)  --->  PostgreSQL 16
   (calls real APIs)            modules: identity, project,          Flyway migrations,
                                conversation, memory, extraction,    FTS (tsvector + GIN),
                                agent, task, handoff, audit,         row-level security,
                                metrics, demo, lab                   JSONB for flexible payloads
```

Packages (`backend/src/main/java/com/memora/...`):

- `common` errors, correlation IDs, request metrics, pagination, idempotency helper.
- `security` JWT issuing and validation (HS256 via Spring Security resource server), agent API keys, `Actor`, tenant context, RLS-aware DataSource.
- `identity` tenants, users, memberships, login, tenant switching.
- `project` projects (workspaces) per tenant.
- `conversation` conversations and messages with atomic sequence numbers.
- `memory` memory records, versioning and supersession, search, context assembly.
- `extraction` `MemoryExtractor` interface plus the deterministic `RuleBasedMemoryExtractor`.
- `agent` agent registry and capabilities.
- `task` versioned tasks with shared JSONB state and an append-only event log.
- `handoff` structured handoffs, inboxes, claim/ack/complete/fail/retry, lease recovery.
- `audit` audit events and overview metrics.
- `demo` deterministic Planner, Developer and Tester agents and the guided workflow.
- `lab` the concurrency lab (in-process concurrent workload with measured results).

## Data model

`tenants`, `users`, `memberships`, `projects`, `agents`, `conversations`, `messages`, `memories`,
`tasks`, `task_events`, `handoffs`, `audit_events`, `lab_runs`. Every tenant-owned table has
`tenant_id` with a foreign key, composite indexes leading with `tenant_id`, and an RLS policy.

## Key decisions

- Tenant comes from the authenticated principal (JWT claim or agent key), never from request bodies.
- Defense in depth: every query filters by tenant in the application, and PostgreSQL RLS
  (`FORCE ROW LEVEL SECURITY`) enforces the same rule per connection via `app.tenant_id`.
- Facts carry a `fact_key` (subject + property) and a `scope_key` (tenant, project or user).
  A partial unique index allows one CURRENT fact per key and scope; a new value supersedes the
  old row (kept as history) inside one transaction with a row lock.
- Tasks and handoffs use JPA `@Version`; stale writes get HTTP 409 with the current version.
- Message sequence numbers come from an atomic `UPDATE ... RETURNING` on the conversation.
- Idempotency keys (per tenant, per table, unique) make retried POSTs return the original record.
- Handoff claims are atomic conditional updates; acknowledged handoffs carry a lease and are
  returned to the inbox by a recovery job when the lease expires.
- No LLM, no embeddings, no paid service. The extractor is rule-based and replaceable.

## API plan (all under `/api/v1`)

auth (`login`, `me`, `switch-tenant`), `tenants`, `projects`, `conversations` and messages,
`memories` (create, get, update, search, history, supersede), `context`, `agents`
(register, list, inbox), `tasks` (create, get, versioned update, events), `handoffs`
(create, get, ack, complete, fail, retry), `overview`, `audit`, `demo`, `lab`. OpenAPI at
`/v3/api-docs`, Swagger UI at `/swagger-ui.html`. Health at `/actuator/health`.

## Milestones and acceptance criteria

1. Vertical slice: create a conversation, store a memory, retrieve it, see it in the UI.
2. Core platform: three memory types, extraction, supersession, search, context, tasks,
   handoffs, auth, tenant isolation, optimistic locking. Integration tests green.
3. Demo: guided workflow, two seeded tenants, concurrency lab with real numbers, polished UI.
4. Verification: all tests and builds run and reported truthfully; docs complete.
