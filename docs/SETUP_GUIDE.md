# Setup guide

Memora needs no API keys, model provider, cloud account or payment method. Choose one of the two paths.

## Path A: Docker Compose (recommended for demos)

Requirements: Docker Engine 24+ with the Compose plugin (or Docker Desktop), about 2 GB free RAM.

```bash
git clone https://github.com/Adi-o-s/engram-graph-memory.git
cd engram-graph-memory
cp .env.example .env
```

Edit `.env` and set three values (any strings you like; they never leave your machine):

| Variable | Purpose |
|---|---|
| `POSTGRES_SUPERUSER_PASSWORD` | Container superuser, used only by the init script |
| `MEMORA_DB_PASSWORD` | Password of the application's database role |
| `MEMORA_JWT_SECRET` | Signs login tokens. 32+ random characters: `openssl rand -base64 48` |

```bash
docker compose up --build
```

The first build downloads Maven and npm dependencies, so it needs internet access once. When the
`frontend` container starts, open http://localhost:3000 on that same machine (nothing is hosted online; the link only works while the stack is running) and sign in as `alice@acme.test` / `memora-demo`.

Stop with `Ctrl+C` or `docker compose down`. Data survives in the `memora-pgdata` volume.
`docker compose down -v` (or `scripts/reset-db.sh docker`) deletes it.

## Path B: local toolchain (recommended for development)

Requirements: JDK 21, Node.js 20+ with npm, PostgreSQL 15 or newer (16 recommended). Maven is not needed;
the Maven Wrapper downloads it.

1. **Database (once).** As a PostgreSQL superuser:
   ```bash
   sudo -u postgres scripts/setup-local-db.sh
   ```
   This creates the role `memora` (password `memora`, not a superuser, so row-level security applies) and
   the databases `memora` and `memora_test`. Override with `MEMORA_DB_USER` / `MEMORA_DB_PASSWORD`.
   On macOS with Homebrew PostgreSQL, run `scripts/setup-local-db.sh` as your own user instead.

2. **Backend.**
   ```bash
   cd backend
   export MEMORA_JWT_SECRET=$(openssl rand -base64 48)
   ./mvnw spring-boot:run
   ```
   Flyway creates the schema and demo data is seeded on first start. Check http://localhost:8080/actuator/health.
   To run the packaged jar instead: `./mvnw -DskipTests package && java -jar target/memora-backend-0.1.0.jar`.

3. **Dashboard.**
   ```bash
   cd frontend
   npm ci
   npm run dev          # http://localhost:5173, proxies /api to http://localhost:8080
   ```
   Point at another backend with `MEMORA_BACKEND_URL=http://host:port npm run dev`.

## Configuration

All settings are environment variables with local defaults (`backend/src/main/resources/application.yml`).

| Variable | Default | Notes |
|---|---|---|
| `MEMORA_DB_URL` | `jdbc:postgresql://localhost:5432/memora` | |
| `MEMORA_DB_USER` / `MEMORA_DB_PASSWORD` | `memora` / `memora` | Must not be a superuser, or RLS is bypassed |
| `MEMORA_DB_POOL_SIZE` | `20` | HikariCP maximum pool size |
| `MEMORA_JWT_SECRET` | random per start | Set it, or tokens become invalid after every restart |
| `MEMORA_TOKEN_TTL` | `PT12H` | ISO-8601 duration |
| `MEMORA_SEED_DEMO_DATA` | `true` | Two tenants, four users, three agents per tenant. Set `false` for real use |
| `MEMORA_DEMO_PASSWORD` | `memora-demo` | Password of the seeded demo users |
| `MEMORA_CORS_ORIGINS` | localhost:5173, 4173, 3000 | Comma-separated browser origins |
| `MEMORA_HANDOFF_LEASE_SECONDS` | `300` | How long an acknowledged handoff stays claimed |
| `MEMORA_HANDOFF_RECOVERY_INTERVAL_MS` | `30000` | How often expired leases are returned to the inbox |
| `PORT` | `8080` | HTTP port |

## Running the tests

| Command | Needs | What it covers |
|---|---|---|
| `cd backend && ./mvnw test` | Docker (Testcontainers starts `postgres:16-alpine`) | 93 unit and integration tests |
| `MEMORA_TEST_DB_URL=jdbc:postgresql://localhost:5432/memora_test ./mvnw test` | the local `memora_test` DB | same tests, no Docker. **The test DB is wiped.** |
| `cd frontend && npm test` | nothing | 11 Vitest tests |
| `cd frontend && npm run e2e` | backend on :8080 and `npm run build && npm run preview` | 8 Playwright browser tests. Set `MEMORA_UI_URL` for another address |
| `java load-test/LoadTest.java [url] [users] [requestsPerUser]` | running backend with demo data | prints measured latency percentiles |

Playwright uses its own Chromium. If it is not installed, run `npx playwright install chromium` once, or set
`PLAYWRIGHT_CHROMIUM_PATH` to an existing Chromium binary.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `localhost:3000` does not open at all ("site can't be reached") | The stack is not running on this machine. Run `docker compose up --build` and wait for the `frontend` container to start. Check with `docker compose ps` |
| `Cannot reach the Memora backend` in the dashboard | Start the backend; check http://localhost:8080/actuator/health |
| Everyone is signed out after a backend restart | Set `MEMORA_JWT_SECRET` to a fixed value |
| Overview says row-level security is **Off** | The app connects as a superuser. Use the `memora` role created by the setup script |
| `password authentication failed for user "memora"` | Run `scripts/setup-local-db.sh`, or set `MEMORA_DB_USER`/`MEMORA_DB_PASSWORD` |
| Flyway error about `ON DELETE SET NULL (column)` | PostgreSQL is older than 15. Upgrade to 15+ |
| `extension "pg_trgm" is not available` | Install the PostgreSQL contrib package (`postgresql-contrib`) |
| Port 5432/8080/3000 already in use | Change `MEMORA_DB_PORT`, `MEMORA_API_PORT`, `MEMORA_UI_PORT` in `.env` |
| `403` on login through a custom proxy | Add the dashboard origin to `MEMORA_CORS_ORIGINS`, or forward the original `Host` header |
| Testcontainers cannot pull images (rate limit) | Use `MEMORA_TEST_DB_URL` with a local PostgreSQL |
| Want a clean slate | `scripts/reset-db.sh docker` or `scripts/reset-db.sh local` |
