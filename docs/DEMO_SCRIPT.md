# Demo script (about 5 minutes)

Setup before presenting: `docker compose up --build` (or the local path), open http://localhost:3000, and
sign in as **alice@acme.test** / `memora-demo`. Everything shown is live data from PostgreSQL. No model API
is called at any point; the agents are deterministic.

## 1. The problem (20 s)

"AI agents forget. Each one keeps its own scratchpad, so a planner, a developer and a tester can disagree about
basic facts. Memora is a shared, multi-tenant memory with history, and a way for agents to hand work to each other."

## 2. Guided demo (2 min) — sidebar: **Guided demo**

1. **Teach.** Click *Start demo*. Six sentences go into a conversation. Point out:
   - "We use MySQL." creates a fact. "We switched from MySQL to PostgreSQL." shows **Superseded Previous**:
     the old fact is kept as history, not deleted.
   - "I prefer small pull requests" becomes a preference; "When an integration test fails…" becomes a
     *procedural* memory. Every memory names the rule that produced it.
2. **Plan.** The Planner reads memory, creates a task with requirements and hands it to the Developer
   (handoff with memory references).
3. **Develop.** The Developer acknowledges, builds context and the log shows
   `history: mysql (SUPERSEDED) -> postgresql (CURRENT)`: it implements against PostgreSQL. Task version goes up.
4. **Test.** The first attempt hits a simulated environment failure. The handoff is **FAILED**, not lost, and the
   Tester records a lesson as procedural memory.
5. **Retry.** The handoff is retried, checks pass, the task is **Done** at version 5. Show the task event log on
   the right: every transition is recorded with its version.

## 3. Memory explorer (45 s)

Search `database`, tick *include superseded*, click the PostgreSQL fact: provenance (source message, extractor),
version history v1 MySQL → v2 PostgreSQL. Optionally click *open conversation* to jump to the original message.

## 4. Tenant isolation (45 s)

Open **Tenant isolation**. The green banner reads row-level security status from PostgreSQL. Click
*Sign in second user* (Bob, Globex Health): his data is completely different (MongoDB). Click *Run probes*:
six cross-tenant requests using Bob's real ids, each returns 404 or empty, all marked **Isolated**.
"The tenant comes from the token, never from the request, and the database enforces it again."

## 5. Concurrency (45 s)

**Concurrency lab**: *Versioned task counter*, 16 threads, 400 operations, *Run*. Point out conflicts detected
(hundreds), zero lost updates, invariants checked in the database. Then **Tasks & handoffs**, open any task,
click *Simulate stale write*: a 409 with expected and current version, nothing overwritten.

## 6. Close (20 s)

"Runs on a laptop, open source stack, no API keys. Agents use it over REST with their own keys; Swagger is at
`/swagger-ui.html`. The extractor is rules today and sits behind an interface, so a local model can replace it."

## Backup answers

- *Why not an LLM for extraction?* Zero cost and deterministic, explainable output. The interface is replaceable.
- *What happens if an agent crashes mid-task?* Its acknowledged handoff has a lease; when it expires the recovery
  job returns it to the inbox. After the attempt limit it goes to dead letter.
- *How do you know numbers are real?* The lab and load test measure on this machine; see `docs/TEST_RESULTS.md`.
