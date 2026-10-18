# Memora

**Memora 2.0: a general-purpose multi-agent orchestration platform built on shared memory.** Define your own
agents (role, instructions, tools, memory scopes, schemas) and workflows (dependencies, parallel steps,
conditions, bounded loops, retries, approvals, completion criteria), and run them on one persisted engine.
Describe a goal and the rule-based planner proposes a workflow you can edit. Software delivery and research
reports ship as examples; both run on the same engine with entirely different agents. See
[docs/ORCHESTRATION_ARCHITECTURE.md](docs/ORCHESTRATION_ARCHITECTURE.md) and the
[orchestration demo](docs/ORCHESTRATION_DEMO.md).

Underneath is the shared memory infrastructure for AI agents. Agents and people write to one PostgreSQL-backed memory:
conversations are stored verbatim, a rule-based extractor turns them into episodic, semantic and
procedural memories, newer facts supersede older ones while keeping history, and agents coordinate
through versioned tasks and structured handoffs. Every tenant is isolated in the application and again
by PostgreSQL row-level security.

**Zero cost.** Memora runs entirely on your machine. It calls no model API, needs no API key, cloud
account or credit card, and every runtime dependency is open source. See
[docs/COST_AND_DEPENDENCY_AUDIT.md](docs/COST_AND_DEPENDENCY_AUDIT.md).

| | |
|---|---|
| Backend | Java 21, Spring Boot 3.5, Spring Security (JWT + agent API keys), JPA, Flyway, PostgreSQL 16 |
| Dashboard | React 19, TypeScript, Vite, Tailwind CSS 4, React Router, Recharts |
| Ops | Docker Compose, Maven Wrapper, npm lockfile |
| Tests | 134 backend tests (JUnit 5, real PostgreSQL), 15 Vitest tests, 11 Playwright browser tests, HTTP load test |

## Quick start (Docker)

```bash
cp .env.example .env
# edit .env: set POSTGRES_SUPERUSER_PASSWORD, MEMORA_DB_PASSWORD and MEMORA_JWT_SECRET
#   (for example: openssl rand -base64 48)
docker compose up --build
```

> Nothing is hosted online. These `localhost` links only open on the machine where `docker compose up` is running.

- Dashboard: http://localhost:3000
- API: http://localhost:8080 · Swagger UI: http://localhost:8080/swagger-ui.html · Health: http://localhost:8080/actuator/health

Sign in with a demo account (password `memora-demo`):

| Account | Tenant | Role |
|---|---|---|
| alice@acme.test | Acme Robotics | admin |
| aaron@acme.test | Acme Robotics | member (sees only their own conversations) |
| bob@globex.test | Globex Health | admin |
| casey@consultant.test | both | member, switches tenants |

Then open **Guided demo** and click through the five steps. [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md)
has a presenter script.

## Run without Docker

Needs JDK 21, Node 20+ and PostgreSQL 15+ (16 recommended).

```bash
sudo -u postgres scripts/setup-local-db.sh        # once: creates role memora/memora, DBs memora + memora_test

cd backend
MEMORA_JWT_SECRET=$(openssl rand -base64 48) ./mvnw spring-boot:run   # http://localhost:8080

cd ../frontend
npm ci
npm run dev                                       # http://localhost:5173 (proxies /api to :8080)
```

Full instructions, every environment variable and troubleshooting: [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md).

## Tests

```bash
cd backend && ./mvnw test        # needs Docker (Testcontainers) or MEMORA_TEST_DB_URL=jdbc:postgresql://localhost:5432/memora_test
cd frontend && npm test          # Vitest unit and component tests
cd frontend && npm run build && npm run preview &   # then, with the backend running:
cd frontend && npm run e2e       # Playwright drives the real dashboard against the real API
java load-test/LoadTest.java http://localhost:8080 32 50   # HTTP load test; prints measured percentiles
```

The backend suite wipes and re-migrates its test database, so never point `MEMORA_TEST_DB_URL` at real data.
Results from the last full run, and known limitations: [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md).

## Reset

```bash
scripts/reset-db.sh docker   # Docker: deletes the volume and starts fresh with demo data
scripts/reset-db.sh local    # local PostgreSQL: drops and recreates the memora database
```

## Orchestration in two minutes

Sign in, open **Plan a workflow**, pick **E-commerce website**, click **Propose a workflow**, then **Save and run
now**. Watch the run's graph (parallel steps, a skipped conditional step, a QA loop that closes a real coverage gap),
inspect any step, approve the handover, and open the artifacts. Then run **Research and report** in the project
*Search engine evaluation* to see different agents on the same engine.

What is and is not intelligent here: agents are **configurations** executed by a deterministic template executor
(tool calls over memory, artifacts and checks) or by an **external** agent through its inbox. Memora calls no
model. The software-delivery agents produce requirements, contracts, a design spec, a schema scaffold, plans and
verification reports, not working application code; assign implementation steps to an external agent for that.

## Documentation

- [docs/ORCHESTRATION_AUDIT.md](docs/ORCHESTRATION_AUDIT.md): what Memora 1.0 could and could not do, and the plan
- [docs/ORCHESTRATION_ARCHITECTURE.md](docs/ORCHESTRATION_ARCHITECTURE.md): engine, data model, guarantees, API
- [docs/AGENT_DEFINITIONS.md](docs/AGENT_DEFINITIONS.md): configurable agents, memory scopes, executors, templates
- [docs/WORKFLOW_BUILDER.md](docs/WORKFLOW_BUILDER.md): workflow specs, validation, running
- [docs/TOOL_SECURITY.md](docs/TOOL_SECURITY.md): tool authorisation, isolation, secrets
- [docs/ORCHESTRATION_DEMO.md](docs/ORCHESTRATION_DEMO.md): the 8-minute orchestration demo

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): modules, data model, isolation, concurrency, API
- [docs/SETUP_GUIDE.md](docs/SETUP_GUIDE.md): installation, configuration, troubleshooting
- [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md): a 5-minute hackathon walkthrough
- [docs/TEST_RESULTS.md](docs/TEST_RESULTS.md): what was run, actual results, known limitations
- [docs/REQUIREMENTS_FROM_USER.md](docs/REQUIREMENTS_FROM_USER.md): what you need to provide (and what you don't)
- [docs/COST_AND_DEPENDENCY_AUDIT.md](docs/COST_AND_DEPENDENCY_AUDIT.md): licences, costs, zero-cost hosting options
- [TASKS.md](TASKS.md): progress tracker

## Using the API from an agent

```bash
# An admin registers an agent; the key is shown once (only its hash is stored).
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@acme.test","password":"memora-demo"}' | jq -r .token)
KEY=$(curl -s localhost:8080/api/v1/agents -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Reviewer","role":"CUSTOM","capabilities":["review"]}' | jq -r .apiKey)

# The agent writes and reads shared memory with its own key.
curl -s localhost:8080/api/v1/memories -H "X-Agent-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"type":"SEMANTIC","content":"The project deploys on Thursdays.","subject":"project","predicate":"deploy.day","value":"thursday"}'
curl -s -X POST localhost:8080/api/v1/context -H "X-Agent-Key: $KEY" -H 'Content-Type: application/json' \
  -d '{"query":"deploy","maxChars":2000}' | jq -r .rendered
```

## Your own agent flow

The guided demo's agents are built in. To run a flow with an agent you registered yourself, see
[scripts/reviewer-flow.sh](scripts/reviewer-flow.sh): a Developer hands a task to a custom "Reviewer" agent,
which acknowledges it with its own key, reads context, records findings and hands the task back. Each step is one
API call, so it doubles as a template. Run it with `REVIEWER_KEY=mem_... scripts/reviewer-flow.sh` (add `STEP=1`
to pause after each step), then watch the result in Tasks & handoffs.
