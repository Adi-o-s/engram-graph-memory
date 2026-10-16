# Memora

Shared memory infrastructure for AI agents. Agents and people write to one PostgreSQL-backed memory:
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
| Tests | 93 backend tests (JUnit 5, real PostgreSQL), 11 Vitest tests, 8 Playwright browser tests, HTTP load test |

## Quick start (Docker)

```bash
cp .env.example .env
# edit .env: set POSTGRES_SUPERUSER_PASSWORD, MEMORA_DB_PASSWORD and MEMORA_JWT_SECRET
#   (for example: openssl rand -base64 48)
docker compose up --build
```

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

## Documentation

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
