# Architecture

Memora is a modular monolith: one Spring Boot service, one PostgreSQL database, one React dashboard.
No message broker, cache or orchestrator is needed at this scale, and every moving part runs locally.

```
 Browser (React dashboard)          AI agents / scripts
        │  Bearer JWT                    │  X-Agent-Key (or Bearer JWT)
        └──────────────┬─────────────────┘
                       ▼
         Spring Boot API  /api/v1  (OpenAPI at /v3/api-docs)
  ┌───────────────────────────────────────────────────────────────┐
  │ security: JWT + agent keys → Actor{tenant, type, id, role}    │
  │           TenantContext → per-connection app.tenant_id (RLS)  │
  │ identity · project · conversation · extraction · memory       │
  │ context · agent · task · handoff · audit · overview · demo · lab │
  └───────────────────────────────────────────────────────────────┘
                       ▼  HikariCP (pool 20)
         PostgreSQL 16: Flyway migrations, FTS (tsvector + GIN),
         pg_trgm keyword index, JSONB, FORCE ROW LEVEL SECURITY
```

## Modules (`backend/src/main/java/com/memora`)

| Package | Responsibility |
|---|---|
| `common` | Problem-detail errors, correlation ids, request metrics, access log, pagination, idempotency helper |
| `security` | JWT issue/verify (HS256), agent API keys (SHA-256 hashed), `Actor`, `TenantContext`, RLS-aware `DataSource` |
| `identity` | Tenants, users, memberships, login, tenant switching |
| `project` | Projects (workspaces) inside a tenant |
| `conversation` | Conversations and messages with atomic sequence numbers, deterministic summaries |
| `extraction` | `MemoryExtractor` interface, `RuleBasedMemoryExtractor`, secret guard, message ingestion |
| `memory` | Memory records, fact supersession, versions, retract/revise, full-text search |
| `context` | Builds a bounded context (facts, procedures, episodes, recent messages) within a character budget |
| `agent` | Agent registry, capabilities, keys, inbox |
| `task` | Versioned tasks with JSONB shared state and an append-only event log |
| `handoff` | Structured handoffs: ack (lease), complete, fail, retry, reject, dead letter, lease recovery |
| `audit`, `overview` | Audit trail and dashboard metrics |
| `demo` | Seed data and three deterministic agents (Planner, Developer, Tester) |
| `lab` | In-process concurrency workloads with invariant checks |

## Data model

`tenants`, `users`, `memberships`, `projects`, `agents`, `conversations`, `messages`, `memories`, `tasks`,
`task_events`, `handoffs`, `audit_events`, `lab_runs`. Tenant-owned tables carry `tenant_id`, use composite
foreign keys `(tenant_id, id)` so a row can only reference rows of the same tenant, and have indexes that lead
with `tenant_id`. Migrations: `V1__core_schema.sql`, `V2__row_level_security.sql`, `V3__keyword_search_index.sql`.

### Memory

- **Types:** `EPISODIC` (what happened), `SEMANTIC` (facts, preferences, decisions, requirements),
  `PROCEDURAL` (how to do things, lessons, rules).
- **Provenance:** source type (message, agent, API, system), source message id, extractor name, author user or agent.
  Original messages are never modified; memories reference them.
- **Facts:** a memory with subject + predicate gets `fact_key = subject|predicate` and a scope key (`tenant`,
  `project:<id>` or `user:<id>`). A partial unique index allows one `CURRENT` row per (tenant, scope, fact key).
  Writing a different value marks the old row `SUPERSEDED`, links both rows and increments the version.
  Writing the same value returns `DUPLICATE`. Writers of the same fact are serialised with
  `pg_advisory_xact_lock`, with a retry if the unique index still reports a race.
- **Lifecycle:** `CURRENT` → `SUPERSEDED` (by a newer value or a revision) or `RETRACTED`. History is kept and
  exposed at `GET /memories/{id}/history`.
- **Search:** generated `tsvector` column (English config) with a GIN index, plus a trigram index for keyword
  fallback. Query terms are sanitised and turned into prefix terms (`term:*`), OR-ed by default. Ranking
  combines text rank, importance, recency and confidence. Filters: type, status/history, project (with or without
  tenant-shared memories), conversation, task, agent, tag, category, minimum importance.

### Extraction

`RuleBasedMemoryExtractor` ("rule-based-v1") splits text into sentences and applies an ordered list of
patterns: technology switches ("we switched from X to Y"), usage ("we use X", "our database is X"),
preferences ("I prefer X over Y", "my favourite X is Y", "we prefer"), decisions, requirements (prefixed or
"must/should"), lessons, "when X, Y" procedures, "always/never" rules, "to X, Y" procedures and past events.
A small technology vocabulary maps names and aliases to categories (database, language, framework…).
It skips questions, negated sentences, fragments under three words and anything that looks like a secret
(passwords, bearer tokens, private keys, common API key formats). Every memory records which rule produced it.

It is pattern matching, not language understanding: unusual phrasings are missed, and it cannot resolve
pronouns or long-range context. The `MemoryExtractor` interface allows a different implementation (for
example a local model) to be added later without changing callers. Structured memories can always be written
directly through `POST /api/v1/memories`.

## Identity and tenant isolation

- Users sign in with email and password (BCrypt) and get a JWT with the tenant id and role. Agents use an
  `X-Agent-Key` header; keys are random, prefixed `mem_`, and stored as SHA-256 hashes.
- The tenant always comes from the authenticated principal. No endpoint accepts a tenant id from the client.
- **Layer 1, application:** every repository query filters by the caller's tenant. A record from another tenant
  is reported as 404, the same as a record that does not exist. Filtering by another tenant's project is a 404.
- **Layer 2, database:** `FORCE ROW LEVEL SECURITY` on 11 tenant tables with the policy
  `tenant_id = current_setting('app.tenant_id')`. `TenantAwareDataSource` sets `app.tenant_id` on every
  connection checkout from the request's `TenantContext`; with no tenant set, policies match nothing. Login and
  agent-key lookup run in an explicit system scope. The application role must not be a superuser (superusers
  bypass RLS); the Compose init script and `setup-local-db.sh` create a `NOSUPERUSER NOBYPASSRLS` role.
- Roles: `ADMIN` (manage projects and agents, see all tenant data), `MEMBER` (sees only their own
  conversations and user-scoped memories), `AGENT`.

## Coordination

- **Tasks** have a JPA `@Version`. Updates must send `expectedVersion`; a mismatch returns
  `409 stale_version` with `currentVersion`. Status changes follow an explicit transition table. Shared state is a
  JSONB object merged with `statePatch`. Every change appends a `task_events` row.
- **Handoffs** carry summary, decisions, memory references, artifacts, next action and the task version they
  were based on. States: `PENDING → ACKNOWLEDGED → COMPLETED`, or `FAILED → (retry) → PENDING`, `REJECTED`,
  and `DEAD_LETTER` after `maxAttempts`. Transitions take a row lock. Acknowledging grants a lease
  (`MEMORA_HANDOFF_LEASE_SECONDS`, default 300); a scheduled job returns expired leases to `PENDING`.
  A partial unique index allows one active handoff per task.
- **Inbox:** `GET /agents/{id}/inbox` lists pending and acknowledged handoffs addressed to an agent.

## Concurrency and reliability

| Concern | Mechanism |
|---|---|
| Lost updates on tasks | Optimistic locking (`@Version`) + explicit expected version → 409 |
| Competing fact writes | Advisory lock per fact key + partial unique index + retry |
| Message ordering | `UPDATE conversations SET message_count = message_count + 1 … RETURNING` gives gapless sequence numbers |
| Retried POSTs | `Idempotency-Key` header; unique `(tenant_id, idempotency_key)` per table returns the original record |
| Counters | Atomic SQL increments (agent handoff counts, memory access counts) |
| Crashed agents | Handoff leases and recovery job; retries up to `maxAttempts`, then dead letter |
| Restarts | All state is in PostgreSQL; verified by `PersistenceRestartIT` and a container restart |

## API

Versioned under `/api/v1`, JSON DTOs, Bean Validation, RFC 7807 problem details with `code` and
`correlationId`. Every response carries `X-Correlation-Id`. Main resources: `auth`, `projects`,
`conversations` (+ messages, summarize), `memories` (+ search, history, revise, retract, extract-preview),
`context`, `agents` (+ inbox, rotate-key), `tasks` (+ events), `handoffs` (+ ack, complete, fail, retry,
reject), `overview`, `audit`, `demo/runs`, `lab/runs`. Full contract in Swagger UI.

Logs include correlation id and tenant id. Passwords, tokens, keys and memory content are not logged.

## Dashboard

React single-page app in `frontend/`. Every view calls the real API through `src/api/endpoints.ts`; there is
no mock data. Views: Overview, Guided demo, Memory explorer, Conversations, Agent workspace (with context
builder), Tasks & handoffs, Tenant isolation (side-by-side sessions and live cross-tenant probes), Concurrency lab.
