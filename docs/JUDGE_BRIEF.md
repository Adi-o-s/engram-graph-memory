# Judge brief

**Memora**: shared graph memory for AI agents, built on FalkorDB. Track 02, Agent Memory and Coordination.
Runs locally with `docker compose up`; no API keys, no model calls, no paid services.

## In one minute

1. `cp .env.example .env`, set the three secrets it names, `docker compose up --build`.
2. Open http://localhost:3000, sign in as `alice@acme.test` / `memora-demo`.
3. **Guided demo** → click **Go** on steps A to E of the graph walkthrough. Then open **Memory graph**.
4. FalkorDB Browser is at http://localhost:3001 (connect to `localhost:6379`); each tenant is a graph named
   `memora_t_<tenant id>`.

## What the graph does (and how to check it)

| Claim | Where to see it | Code | Test |
|---|---|---|---|
| Agent context is a graph traversal that returns the path to every memory | Walkthrough step C: `retrieval: graph`, path chips, "2 hops" | `GraphContextRetriever` (`BY_ENTITY`, `BY_TASK`, …) | `GraphMemoryIT.graphRetrievalFindsATwoHopFactThatKeywordSearchMisses`, `e2e/graph.spec.ts` |
| A changed fact alerts the agents that read the old version, and only them | Walkthrough step D: Coder alerted, Reviewer not | `GraphAlerts.RAISE_FOR_READERS`, `OPEN_FOR_AGENT` | `GraphMemoryIT.anAgentThatReadAChangedFactIsWarnedAndOneThatDidNotIsNot` |
| "Why does the agent know this?" is a path plus provenance | **Why?** on any memory | `GraphExplainService` | `GraphMemoryIT` (explain) |
| One graph per tenant, chosen from the signed-in tenant only | Walkthrough step E; Browser graph list | `TenantGraphs.of(actor)` | `GraphMemoryIT.tenantsHaveSeparateGraphsAndForgedIdsFindNothing`, `TenantIsolationIT` |
| Agents query the graph as tools | Agent studio: `graph.who_knows`, `graph.impact`, `graph.neighbors`, `graph.explain` | `GraphTools`, `GraphInsights` | planner template runs `graph.impact` |
| Central entities by PageRank | Memory graph page, "Key entities" | `GraphInsights.KEY_ENTITIES` (`algo.pageRank`) | `GraphMemoryIT.keyEntitiesAreRankedByPageRank` |
| The graph can always be rebuilt from PostgreSQL | `POST /api/admin/graph/rebuild` | `GraphRebuildService` | `GraphMemoryIT.rebuildReconstructsTheSameGraphFromPostgres`, `GraphRestartIT` |

Every Cypher statement is in [GRAPH_QUERIES.md](GRAPH_QUERIES.md), verbatim; a test fails if that file drifts
from the code.

## Design in three sentences

PostgreSQL is the system of record (auth, row-level security, task versions, handoff leases); FalkorDB holds the
memory and coordination graph that context, explanations, alerts and graph tools read. A transactional outbox
(database triggers in the same transaction) feeds an idempotent projector, and readers apply their tenant's
pending rows first, so an agent reads its own writes. Ids from the graph are re-checked in PostgreSQL before
anything reaches a client. Details: [README](../README.md#why-two-databases), [ARCHITECTURE.md](ARCHITECTURE.md).

## Numbers (measured; commands in [TEST_RESULTS.md](TEST_RESULTS.md))

- 145 backend, 15 Vitest and 12 Playwright tests pass.
- Retrieval eval on a synthetic 12-service project: recall@6 0.97 (graph) vs 0.50 (keyword); graph context is
  slower. The dataset is built with two-hop facts on purpose, and the doc says so.
- 400 concurrent memory writes: the graph lagged PostgreSQL by at most about 1.5 s and caught up 237 ms after the
  last write; no projection failures.

## Honest limits

- Memory extraction is rule-based (no LLM): it understands phrasings like "X uses Y", "X deploys through Y",
  "Y runs on Z" and "We moved X to Y", not arbitrary prose.
- PageRank counts superseded relationships too, because `algo.pageRank` takes no edge filter.
- Not verified: a clean-clone `docker compose up --build` from the build sandbox, several backend instances
  sharing one outbox, sustained load beyond the burst above.
- AI wrote much of this code; see the AI disclosure at the end of the README.
