# Test results

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
