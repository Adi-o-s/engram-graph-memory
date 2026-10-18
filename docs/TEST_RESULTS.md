# Test results

## FalkorDB graph memory (run on 2026-10-18)

Run in the build environment: a Linux container with 4 vCPUs and 15 GB RAM, OpenJDK 21.0.12, Maven 3.9.11,
Node 22.22, PostgreSQL 16.15, FalkorDB `falkordb/falkordb:v4.14.6` in Docker (container named `falkor`). The
backend, both databases, the browser and the load all shared the same 4 CPUs, and the host was visibly
contended during part of the session, so treat latencies as rough. Nothing here is estimated.

| Suite | Command | Result |
|---|---|---|
| Backend unit + integration | `MEMORA_TEST_DB_URL=jdbc:postgresql://localhost:5432/memora_test MEMORA_TEST_FALKORDB_URL=redis://localhost:6379 MEMORA_TEST_FALKORDB_CONTAINER=falkor mvn -B test` | **145 passed**, 0 failed, 0 skipped (134 existing + 11 new) |
| Dashboard unit/component | `cd frontend && npm test` | **15 passed** |
| Dashboard type check + build | `npm run build` | passed |
| Browser end-to-end | `npm run build && npm run preview`, then `PLAYWRIGHT_CHROMIUM_PATH=… npx playwright test` against the local backend + FalkorDB | **12 passed** (11 existing + `e2e/graph.spec.ts`) |

Without the three `MEMORA_TEST_*` variables the same tests start PostgreSQL and FalkorDB with Testcontainers;
that path was not run here.

| New class | Tests | What it checks |
|---|---|---|
| `GraphMemoryIT` | 7 | projection and idempotent replay; graph context finds a 2-hop fact keyword search misses, with its path and explain provenance; a revised fact supersedes the old node and leaves context; the agent that read a changed fact is alerted once and one that did not is not; tenants have separate graphs and forged ids find nothing; key entities ranked by PageRank; rebuild from PostgreSQL gives identical counts, members get 403 |
| `GraphRestartIT` | 1 | `docker restart` of FalkorDB: the graph survives and projection resumes |
| `GraphQueriesDocTest` | 1 | `docs/GRAPH_QUERIES.md` contains every Cypher constant in the code verbatim |
| `RetrievalEvalIT` | 1 | the retrieval eval below |
| `ProjectorThroughputIT` | 1 | the projector measurement below |

Two existing tests changed because the schema intentionally changed: `MigrationIT` expects migrations 1–5 and
forced RLS on `graph_outbox`. All graph tests run against a real FalkorDB v4.14.6, not a mock.

### Retrieval eval: graph vs keyword

`RetrievalEvalIT` builds one project through the API: 12 services, each with three direct facts
("X uses DynamoDB", "X uses Kafka", "X deploys through Jenkins"), plus one hosting fact per technology
("Kafka is hosted on Confluent Cloud"), 45 memories in all. The query for service X is "fix the X outage". Its
relevant set is its 3 direct facts plus the 3 hosting facts of its technologies, which never mention X and are
two hops away in the graph. recall@k is the share of the relevant facts in the first k facts of
`POST /api/v1/context`, with `retrieval` set per request. **The dataset is synthetic and built to contain
multi-hop facts, which is the case graph retrieval is for**; it shows where each mode's recall comes from, not
how Memora does on your data.

Command: `MEMORA_TEST_DB_URL=… MEMORA_TEST_FALKORDB_URL=redis://localhost:6379 mvn -B test -Dtest=RetrievalEvalIT`
(prints the table and writes `backend/target/retrieval-eval.md`). Result from the full-suite run above:

| Mode | k | recall@k (all) | direct facts | 2-hop facts | mean latency (ms) |
|---|---|---|---|---|---|
| graph | 3 | 0.50 | 1.00 | 0.00 | 194.6 |
| graph | 6 | **0.97** | 1.00 | 0.94 | 119.4 |
| graph | 10 | **1.00** | 1.00 | 1.00 | 135.6 |
| hybrid | 3 | 0.50 | 1.00 | 0.00 | 196.1 |
| hybrid | 6 | 0.97 | 1.00 | 0.94 | 245.2 |
| hybrid | 10 | 1.00 | 1.00 | 1.00 | 286.8 |
| keyword | 3 | 0.50 | 1.00 | 0.00 | 171.7 |
| keyword | 6 | **0.50** | 1.00 | 0.00 | 76.0 |
| keyword | 10 | **0.50** | 1.00 | 0.00 | 53.3 |

- An earlier standalone run of the same test (`-Dtest=RetrievalEvalIT`, with MongoDB where the final version
  uses DynamoDB) gave graph recall@6 **1.00** and mean latencies of 29–44 ms for graph and about 17 ms for
  keyword at k = 6 and 10. The 0.94 above comes from ties
  at two hops between the hosting facts and other services' facts that mention the same technology.
- Latency is measured around the HTTP call in the test and includes the read-your-writes catch-up and the
  PostgreSQL re-check. Graph context is slower than keyword context.
- The **first** run of this eval, before a scoring change, gave graph recall@6 **0.22** (keyword 0.50): full-text
  hits on common words such as "service" scored like direct entity hits and crowded the budget. Full-text and
  fill-up hits now score like 3 extra hops (`GraphContextRetriever.TEXT_WEIGHT`), so a memory reached by edges
  outranks one that only shares a word with the query.

### Projector throughput and outbox lag

`ProjectorThroughputIT`: 8 client threads write 400 memories through `POST /api/v1/memories` while a sampler
reads the outbox lag every 20 ms. Only the background projector (poll every 250 ms, batches of 200) drains the
outbox. Command: `… mvn -B test -Dtest=ProjectorThroughputIT` (writes `backend/target/projector-throughput.md`).
Result from the full-suite run:

| Measure | Value |
|---|---|
| Memories written through the API (8 threads) | 400 |
| Outbox rows applied by the projector | 400 |
| Write phase (all API calls returned) | 4.94 s (81 writes/s) |
| Outbox empty after the last write returned | 237 ms |
| Rows applied per second over the run (bounded by the write rate) | 77 rows/s |
| Highest pending rows seen (sampled every 20 ms) | 110 |
| Oldest pending row age, highest seen | 1514 ms |
| New FAILED rows | 0 |

The projector kept up with the API: the graph was at most about 1.5 s behind PostgreSQL during the burst and
caught up within 237 ms of the last write. Context reads do not wait for the poller; they apply their tenant's
pending rows first. This does not measure the projector's ceiling, because the write rate was the bottleneck.

Not verified: `docker compose up --build` from a clean clone with the FalkorDB service (the build sandbox could
not download dependencies inside image builds), several backend instances sharing one outbox, and FalkorDB
under sustained load beyond this burst.

---

## Memora 2.0 orchestration (run on 2026-10-18)

Run on a Windows 11 laptop with Docker Desktop. Backend tests ran in an `eclipse-temurin:21-jdk` container against
a dedicated `postgres:16-alpine` container with a non-superuser role (`MEMORA_TEST_DB_URL`), so the application's
own database was never touched. Dashboard build and Vitest ran in `node:22`; Playwright ran in the official
`playwright:v1.56.1-noble` image against the running Docker Compose stack.

| Suite | Result |
|---|---|
| Baseline before any change | **93 passed**, 0 failed |
| Backend unit + integration after the change (`./mvnw test`) | **134 passed**, 0 failed, 0 skipped (93 existing + 41 new) |
| Dashboard type check + production build | passed |
| Dashboard unit/component (Vitest) | **15 passed** (11 existing + 4 new) |
| Browser end-to-end (Playwright) against the Docker stack | **11 passed** (8 existing + 3 new) |
| Migration on the existing Docker database (v3 → v4) | applied cleanly; existing data kept; defaults installed for both tenants |
| Hard kill of the backend container mid-step (`docker compose kill backend`) | step detected at startup (`lease_expired`), retried, run SUCCEEDED; completed steps not re-run |

Two existing tests were updated because the schema intentionally changed: `MigrationIT` now expects migrations
1–4 and checks forced row-level security on the 10 new tables as well.

| New class | Tests | What it checks |
|---|---|---|
| `WorkflowDemoIT` | 3 | full-stack delivery (parallel layers, dependent waits, QA loop + remediation, conditional skip, approval, completion criteria, artifacts, tasks, handoffs, decisions in memory); research workflow on the same engine with different agents (fact-check loop, report v2); classic Planner/Developer/Tester as configuration |
| `WorkflowEngineIT` | 9 | parallel vs dependent steps with timestamps, conditional routing with traces, bounded retries with injected faults, permanent failures not retried, exhausted retries block downstream until a manual retry, completion criteria decide success, pause/resume without re-running, cancel, loop exhaustion, graph/reference/input validation, external agent completes a step through the handoff API |
| `AgentDefinitionIT` | 5 | create/update/duplicate/activate/version, stale writes, invalid tools/capabilities/executors/policies/schemas, instructions cannot grant permissions, templates/tools/executors, member restrictions, seeded agents linked |
| `OrchestrationSecurityIT` | 6 | cross-tenant access to every new resource returns 404, members cannot administer or approve, out-of-policy memory access denied and audited, approval-gated and unavailable tools, secrets refused and redacted, handoff isolation |
| `WorkflowMemoryIT` | 3 | later runs see project decisions but never another run's working memory, provenance, architect follows the current (superseded) database fact, USER scope means the starter only |
| `WorkflowRecoveryIT` | 2 | 8 concurrent claimers racing the scheduler never claim a step twice; expired lease retried and late/forged results ignored |
| `WorkflowRestartIT` | 1 | stop one backend, simulate a crashed step, start another: completed steps kept, crashed step recovered, run resumed |
| `ExpressionsAndConditionsTest`, `RequirementExtractorTest`, `WorkspaceToolsTest`, `TaskPathTest` | 12 | templates and fallbacks, conditions, schema subset, requirement extraction, path traversal and symlink escape, task transitions |

Not verified: multiple backend instances against one database (the claim and fencing logic is designed for it and
tested with concurrent claimers in one process; set `memora.workflow.recover-on-startup=false` in that setup),
`command.run` with a real allow-listed command (covered only as unavailable/denied), and load testing of the
workflow engine.

---

## Memora 1.0

Everything below was actually run on 2026-10-16 in the build environment: a Linux container with 4 vCPUs and
15 GB RAM, OpenJDK 21.0.12, Node 22.22, PostgreSQL 16.15. The backend, database and load generator shared the
same 4 CPUs, so absolute numbers are modest and will differ on your machine. Nothing here is estimated.

## Summary

| Suite | Command | Result |
|---|---|---|
| Backend unit + integration | `./mvnw test` (Testcontainers, `postgres:16-alpine`) | **93 passed**, 0 failed, 0 skipped |
| Backend unit + integration | `MEMORA_TEST_DB_URL=… ./mvnw test` (local PostgreSQL 16.15) | **93 passed**, 0 failed, 0 skipped |
| Dashboard unit/component | `npm test` (Vitest) | **11 passed** |
| Dashboard type check + build | `npm run build` | passed |
| Browser end-to-end | `npm run e2e` (Playwright, Chromium) against local backend | **8 passed** |
| Browser end-to-end | same tests against the Docker Compose stack (nginx → backend → PostgreSQL) | **8 passed** |
| HTTP load test | `java load-test/LoadTest.java` at 8, 32 and 64 users | **0 errors** in 5,600 requests |
| Concurrency lab | 16 threads × 400 operations, three scenarios | **all invariants passed** |

## Backend tests by class

| Class | Tests | What it checks |
|---|---|---|
| `RuleBasedMemoryExtractorTest` | 16 | each extraction rule, skipped questions/negations/secrets |
| `TaskStatusTest` | 4 | allowed and rejected status transitions |
| `MemorySearchQueryTest` | 4 | safe tsquery construction from arbitrary input |
| `MemoryLifecycleIT` | 14 | three memory types, FTS with stemming/prefixes, supersede with history, duplicates, project-scoped facts, revise, lock versions, retract, expiry, validation errors, secret refusal, idempotency, pagination |
| `ConversationIT` | 7 | ordered persistence and pagination, extraction with provenance, supersession via conversation, per-user preferences, idempotent retries, summaries, unknown ids |
| `ContextIT` | 4 | project scoping and bounds, character budget never exceeded, atomic access counts, invalid limits |
| `TaskConcurrencyIT` | 6 | stale version → 409 with current version, exactly one winner per version, retry loop loses no update, transitions and history, required version, idempotent creation |
| `HandoffIT` | 10 | structured payload and reassignment, idempotent ack, only the receiver acts, retry then dead letter, lease expiry recovery, one active per task, stale task version, tenant-checked memory refs, sender identity, idempotent create |
| `ConcurrentWritesIT` | 4 | gapless concurrent message sequence numbers, one CURRENT fact under concurrent writers, concurrent retries with one idempotency key, concurrent distinct writes all stored |
| `TenantIsolationIT` | 9 | cross-tenant reads/writes/references → 404, foreign project filters → 404, agent keys bound to tenant, member visibility, RLS without app filters, tenant switching |
| `AuthIT` | 6 | tenant-scoped token, same answer for wrong password and unknown user, foreign tenant login refused, protected vs public endpoints, correlation ids |
| `DemoWorkflowIT` | 3 | Planner → Developer → Tester completes a task through persisted handoffs, steps are retry-safe, demo runs invisible to other tenants |
| `ConcurrencyLabIT` | 2 | lab scenarios report passing invariants |
| `MigrationIT` | 3 | migrations apply cleanly to an empty database, every tenant table has forced RLS, search and tenant indexes exist |
| `PersistenceRestartIT` | 1 | data written before an application restart is readable after it |

Persistence was also checked with Docker: 17 messages before `docker compose restart backend`, 17 after.

## Load test (HTTP, mixed workload)

Workload per request: 50% full-text memory search, 20% context building, 20% message append with extraction,
10% memory write. One shared conversation, one tenant, all requests authenticated.

```
Memora load test against http://localhost:8080
virtual users 8, requests per user 100, total 800, wall time 2.03 s, throughput 394.0 req/s
operation          count  errors    p50 ms    p95 ms    p99 ms    max ms
append+extract       169       0      22.5      45.7      67.5      72.5
context              168       0      23.7      49.6      66.1      78.5
memory write          84       0      19.5      40.4      55.8      55.8
search               379       0      12.4      29.9      54.2      62.2
ALL                  800       0      17.4      42.2      59.4      78.5
```

```
Memora load test against http://localhost:8080
virtual users 32, requests per user 50, total 1600, wall time 4.61 s, throughput 347.1 req/s
operation          count  errors    p50 ms    p95 ms    p99 ms    max ms
append+extract       307       0     101.2     286.7     319.8     552.3
context              329       0     105.4     243.0     305.6     366.4
memory write         161       0      68.6     266.8     509.6     542.8
search               803       0      46.9     130.6     196.7     314.0
ALL                 1600       0      66.2     206.0     307.3     552.3
```

```
Memora load test against http://localhost:8080
virtual users 64, requests per user 50, total 3200, wall time 7.28 s, throughput 439.5 req/s
operation          count  errors    p50 ms    p95 ms    p99 ms    max ms
append+extract       666       0     194.7     440.4     590.6     814.7
context              617       0     175.4     379.4     470.9     567.4
memory write         329       0      94.1     227.7     307.7     371.4
search              1588       0      79.6     202.2     281.7     417.6
ALL                 3200       0     115.6     328.9     466.4     814.7
```

Throughput levels off around 400 requests/s on this 4-CPU container, and latency grows with concurrency as
requests queue for CPU and the 20-connection pool. Message appends to a *single* conversation serialise on that
conversation's row by design (gapless sequence numbers), which is the worst case for that operation.

## Concurrency lab (in-process, 16 threads × 400 operations)

| Scenario | Succeeded | Conflicts / retries | Duration | p50 / p95 / p99 | Invariants |
|---|---|---|---|---|---|
| Versioned task counter | 400 / 400 | 1,492 / 1,492 | 2,228 ms | 15.9 / 480.0 / 709.9 ms | counter = 400, version advanced 400 times |
| Concurrent message append | 400 / 400 | 0 | 726 ms | 19.6 / 81.4 / 104.8 ms | 400 stored, seq unique and gapless, counter matches |
| Competing fact writes | 400 / 400 | 0 | 1,079 ms | 47.9 / 97.4 / 111.5 ms | exactly one CURRENT, 200/200 episodic writes stored |

The task counter is deliberately the hardest case: 16 writers fight over one row, so most attempts conflict and
retry with backoff. Its high p95 is the cost of correctness under extreme contention, and no update was lost.

## Not verified here

- **Docker image build stages** (`./mvnw` and `npm ci` inside `docker build`) could not run in the build
  environment because its sandbox blocks outbound downloads from inside build containers. The runtime stages were
  verified by building images from the locally built jar and `dist/` with the same base images, nginx config and
  Compose file; the full stack then passed all 8 browser tests. The first `docker compose up --build` on a
  normal machine is the first full run of those build stages.
- Docker Hub rate-limited direct pulls; images were pulled through Google's public Docker Hub mirror and
  re-tagged. This does not affect your machine.
- Browsers other than Chromium, and Windows/macOS hosts, were not tested.

## Known limitations

- **Extraction is pattern matching.** It recognises the phrasings documented in `ARCHITECTURE.md`, misses
  others, does not resolve pronouns, and treats each sentence independently. Structured writes via the API
  are always available.
- **Search is lexical** (PostgreSQL full-text + trigram). There are no embeddings, so synonyms only match if
  they share words. The English text-search configuration is used for stemming.
- **Summaries are deterministic** (counts, extracted facts, first/last messages), not abstractive.
- **Single node.** Scheduling (lease recovery) and the concurrency lab run in-process; running several backend
  instances is safe for data (all coordination is in PostgreSQL) but each instance runs its own recovery job.
- **No tenant or user administration API yet.** Tenants, users and memberships are created by the demo seeder
  (through `IdentityAdminService`); there is no sign-up, invitation, password reset or SSO. Agents and projects do
  have admin APIs.
- **JWTs cannot be revoked** before they expire (12 h default); rotate `MEMORA_JWT_SECRET` to invalidate all.
- **No rate limiting** on the API.
- **Lab and demo runs leave data behind** (tasks, demo projects, episodic memories); use `scripts/reset-db.sh`.
- The dashboard bundle is a single ~720 kB chunk (213 kB gzipped); no code splitting yet.
