# What you need to provide

## Required to run Memora

Nothing external. On your own machine you need:

1. **Software** (all free): either Docker with Compose, or JDK 21 + Node 20+ + PostgreSQL 15+.
2. **Three local secrets** in `.env` (Docker) or environment variables (local run), which you invent yourself:
   `POSTGRES_SUPERUSER_PASSWORD`, `MEMORA_DB_PASSWORD`, `MEMORA_JWT_SECRET` (`openssl rand -base64 48`).
   They never leave your machine and must not be committed (`.env` is git-ignored).
3. **Internet once**, for the first `docker compose up --build` or `./mvnw` / `npm ci` to download dependencies.

## Decisions only you can make (none block the demo)

| Decision | Default today | When it matters |
|---|---|---|
| Keep demo data on? | `MEMORA_SEED_DEMO_DATA=true` | Set `false` before storing anything real; demo users have a published password |
| Where to host, if anywhere | your laptop | Only if you want a public URL; see the cost audit for zero-cost options and their limits |
| Merge the pull request | open for review | When you are happy with the code |

## You do NOT need to provide

- Any OpenAI, Anthropic or other model/API key. Memora calls no model API.
- An embedding service or vector database.
- A cloud account, credit card or paid plan of any kind.
- A domain name, TLS certificate or email provider for local use.
- Redis, Kafka, Kubernetes or any other infrastructure beyond PostgreSQL.
