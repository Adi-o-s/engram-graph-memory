# Demo video script (3 minutes)

Six segments, about 30 seconds each. Everything shown is the real app on `docker compose up`; nothing is mocked
and no model API is called. Before recording, reset to a clean database (`docker compose down -v`, then
`docker compose up --build`) so the seeded agents and the guided demo start fresh.

Open two browser tabs: the dashboard (http://localhost:3000, or 3001/your `MEMORA_UI_PORT`) and FalkorDB Browser
(http://localhost:3001 by default, set by `MEMORA_FALKORDB_BROWSER_PORT`; connect to host `localhost`, port 6379,
because the Browser runs inside the FalkorDB container).

Sign in as `alice@acme.test` / `memora-demo` and open **Guided demo**. The top section, **Graph walkthrough
(FalkorDB)**, has steps A–E; each has a **Go** button.

## 1. The problem (0:00–0:25)

Show the Guided demo page, not yet run.

> "Agents that work together forget what each other learned, and when a fact changes, nobody tells the agent
> that already read the old one. Memora gives a team of agents one shared memory, stored as a graph in FalkorDB,
> one graph per tenant. Everything here runs locally, with no API keys."

## 2. Alice teaches the project (0:25–0:55)

Click **Go** on **A. Ingest a conversation**. Three lines appear with the memory each one produced:

- "billing-service uses MySQL." → `billing-service uses MySQL as its database.`
- "billing-service deploys through ArgoCD." → `billing-service deploys through ArgoCD.`
- "ArgoCD runs on Kubernetes." → `argocd is hosted on Kubernetes.`

Click **Go** on **B. See the entities appear**.

> "A rule-based extractor, no LLM, turns the conversation into memories. PostgreSQL stores them; a transactional
> outbox projects them into FalkorDB as Memory nodes linked to Entity nodes. Here are billing-service's
> neighbours, read straight from the graph."

## 3. The Coder gets context with paths (0:55–1:30)

Click **Go** on **C. Coder asks for context**. Point at `retrieval: graph` and at the last fact,
`argocd is hosted on Kubernetes. 2 hops`, with its path
`Entity billing-service -RELATES→ Entity argocd -ABOUT→ Memory`. Click its **Why?** button.

> "The Coder asks to fix the billing-service deploy. Its context is a Cypher traversal: it starts at the
> billing-service entity and walks up to two hops. The Kubernetes fact never mentions billing-service, so a
> keyword search can't find it, but the graph reaches it through ArgoCD. Every memory comes with the path that
> reached it, and Why? shows where it came from."

Optional, if time allows: scroll to **Orchestration demo**, run **1. Teach** and **2. Plan** to show the Planner
handing a task to the Developer; the handoff is a `Handoff` node with `FROM`, `TO`, `FOR` and `CARRIES` edges.

## 4. A fact changes (1:30–2:05)

Click **Go** on **D. Change a fact**. Alice says "We moved billing-service to Postgres."

Point at the amber alert under **Coder**: the old MySQL fact struck through, the new PostgreSQL fact, and the path
`Agent Coder -READ→ Memory (old) -SUPERSEDED_BY→ Memory (new)`. Point at **Reviewer**: no alerts.

> "The MySQL memory is superseded. The graph knows the Coder READ it, so the Coder's next context opens with
> 'changed since you last read it'. The Reviewer never read it, so it gets nothing. That's one traversal over
> READ and SUPERSEDES edges."

## 5. Tenants are separate graphs (2:05–2:30)

Click **Go** on **E. Switch tenants**. The table shows Acme's graph has the billing-service entity and Globex's
does not. Switch to the FalkorDB Browser tab and show the graph list: one `memora_t_<tenant>` graph per tenant.
Run in Browser on the Acme graph:

```cypher
MATCH p=(:Entity {key:'billing-service'})-[:RELATES]->(:Entity)<-[:ABOUT]-(m:Memory) RETURN p
```

> "Each tenant is its own graph, and the graph name comes from the signed-in tenant, never from the request.
> Bob at Globex can't even name Acme's graph."

## 6. Measured, not claimed (2:30–3:00)

Open **Memory graph** in the dashboard (force-directed view, PageRank key entities on the right), then show
`docs/TEST_RESULTS.md`, section "FalkorDB graph memory".

> "These numbers come from tests in the repo. On a synthetic retrieval eval of 12 services with direct and
> two-hop facts, graph retrieval found 97% of the relevant facts at k=6 and all of them at k=10; keyword search
> found the direct ones only, 50%.
> The full suite, including Playwright tests for this walkthrough, passes. The commands are in the doc.
> Memora: shared graph memory for agents, on FalkorDB."

Before recording, re-check the two numbers in segment 6 against `docs/TEST_RESULTS.md`; if you re-run the eval,
use what it prints.
