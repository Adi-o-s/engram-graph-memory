# Deploying: dashboard on Vercel, backend on a server

Vercel hosts static sites and short serverless functions. It cannot run the Spring Boot backend, PostgreSQL or
FalkorDB, so Memora deploys in two halves:

```
browser ──HTTPS──> Vercel (dashboard, frontend/)
                     │  /api/*, /v3/*, /swagger-ui*, /actuator/health are proxied (vercel.json rewrites)
                     ▼
                   your server ──Caddy (HTTPS, 80/443)──> backend:8080 ──> postgres, falkordb (private)
```

Because Vercel proxies `/api` through the dashboard's own origin, the browser never calls the backend directly:
no CORS change, and Vercel preview deployments work too.

## 1. Backend server

Any Linux machine with Docker, about 2 GB RAM (4 GB comfortable), ports 80 and 443 open to the internet.

```bash
git clone https://github.com/Adi-o-s/engram-graph-memory.git && cd engram-graph-memory
cp .env.example .env
# Set strong values: POSTGRES_SUPERUSER_PASSWORD, MEMORA_DB_PASSWORD, MEMORA_FALKORDB_PASSWORD,
# MEMORA_JWT_SECRET (openssl rand -base64 48), and MEMORA_API_DOMAIN.
docker compose -f docker-compose.server.yml up -d --build
curl https://$MEMORA_API_DOMAIN/actuator/health   # {"status":"UP",...}
```

`MEMORA_API_DOMAIN` is the API's public hostname. Caddy gets a Let's Encrypt certificate for it automatically.
Without a domain of your own, use the free `sslip.io` name for the server's IP: `203-0-113-5.sslip.io` for
`203.0.113.5`. With a domain, point an `A` record at the server and use that name.

`docker-compose.server.yml` differs from the local `docker-compose.yml` on purpose:

- Only Caddy publishes ports. PostgreSQL, FalkorDB, its Browser and the backend are reachable only inside the
  Compose network (Docker-published ports bypass host firewalls such as `ufw`).
- FalkorDB requires `MEMORA_FALKORDB_PASSWORD`.
- No dashboard container (Vercel serves it).
- The concurrency lab is capped at 16 threads and 2000 operations (`MEMORA_LAB_MAXTHREADS`, `MEMORA_LAB_MAXOPERATIONS`).
- The backend has a 1 GB memory limit (`MEMORA_BACKEND_MEM_LIMIT`) and every service restarts automatically.

Demo data stays on (`MEMORA_SEED_DEMO_DATA=true`, accounts listed on the login page). Set it to `false` before
anyone stores real data; note that there is no sign-up API yet, so only seeded users can log in.

## 2. Dashboard on Vercel

1. In `frontend/vercel.json`, replace every `MEMORA_API_HOST` with the value of `MEMORA_API_DOMAIN`, and commit.
   (Vercel rewrites cannot read environment variables, so the host lives in this file.)
2. In Vercel: **Add New → Project**, import `Adi-o-s/engram-graph-memory`, set **Root Directory** to `frontend`.
   Framework, build command and output directory come from `vercel.json`. No environment variables are needed.
3. Deploy, open the `*.vercel.app` URL and log in.

## Things that break a deployment

- Changing `MEMORA_DB_PASSWORD` after the first start: the database role is created only when the Postgres volume
  is first initialised. Change it with `ALTER ROLE` inside Postgres as well.
- `docker compose ... down -v` deletes the database and graph volumes. Use plain `down`.
- Editing an existing Flyway migration (`V1`–`V5`): startup fails on the checksum. Add a new `V6__*.sql` instead.
- Changing `MEMORA_JWT_SECRET` logs everyone out.
- Running two backend instances on one database is untested; if you do, set `MEMORA_WORKFLOW_RECOVER_ON_STARTUP=false`.
- Back up PostgreSQL (`pg_dump`). The FalkorDB graph can be rebuilt from it with `POST /api/v1/admin/graph/rebuild`.
