# Cost and dependency audit

**Result: Memora has no runtime cost and no paid dependency.** It makes no outbound network calls at runtime
(no model APIs, telemetry or license checks). Checked by reading `pom.xml`, `package.json` and the source:
the only network connections the backend opens are to PostgreSQL; the dashboard talks only to the backend.

## Runtime dependencies

| Component | Licence | Cost |
|---|---|---|
| OpenJDK 21 (Eclipse Temurin image) | GPLv2 with Classpath Exception | free |
| Spring Boot 3.5 (web, data-jpa, security, oauth2-resource-server, validation, actuator) | Apache 2.0 | free |
| Hibernate ORM 6.6 | LGPL 2.1 | free; used unmodified as a library |
| Flyway (community edition, core + PostgreSQL module) | Apache 2.0 | free |
| PostgreSQL JDBC driver | BSD-2-Clause | free |
| springdoc-openapi 2.8 | Apache 2.0 | free |
| Nimbus JOSE + JWT (via Spring Security) | Apache 2.0 | free |
| PostgreSQL 16 (+ `pg_trgm` contrib extension) | PostgreSQL Licence | free |
| React 19, React Router 7, Recharts 3, Vite, Tailwind CSS 4 | MIT | free |
| lucide-react icons | ISC | free |
| nginx (frontend container) | BSD-2-Clause | free |

## Development and test dependencies

| Component | Licence | Notes |
|---|---|---|
| JUnit 5, Mockito, AssertJ (via spring-boot-starter-test) | EPL 2.0 / MIT / Apache 2.0 | free |
| Testcontainers | MIT | optional; tests also run against a local PostgreSQL |
| Vitest, Testing Library, jsdom, TypeScript | MIT / Apache 2.0 | free |
| Playwright | Apache 2.0 | downloads a Chromium build; free |
| Maven Wrapper | Apache 2.0 | downloads Maven from Maven Central; free |

**Docker:** Docker Engine (Linux) is free and open source. Docker Desktop is free for personal use, education,
open-source projects and small businesses, but Docker's terms require a paid subscription for larger companies.
Check the current Docker Desktop terms if that applies to you, or use Docker Engine, Podman or Colima, or the
non-Docker path in the setup guide.

## Hosting with zero cost

Memora needs a JVM process (about 300–500 MB RAM) and a PostgreSQL 15+ database that allows `pg_trgm`, row-level
security and a non-superuser application role.

| Option | Cost | Card needed | Limitations |
|---|---|---|---|
| Your own laptop or desktop | free | no | Only reachable on your network unless you add a tunnel |
| A machine or VM you already have (home server, school/lab server) | free | no | You maintain it |
| Free tiers of cloud platforms | free within limits | varies | See below |

Free cloud tiers change often, so this section names no provider as a recommendation. When evaluating one, check:
whether signing up requires a credit card (several "always free" VM tiers do, for identity verification); whether
free web services sleep after inactivity (the first request then takes tens of seconds while the JVM starts);
whether a free PostgreSQL database expires or is deleted after a period; and whether the database lets you create a
non-superuser role (row-level security does not apply to superusers). Do not enable any paid add-on to make
Memora work; it does not need one.

Nothing has been deployed anywhere. All verification was local.

## Memora 2.0 orchestration

Re-checked after adding orchestration: **no new runtime dependency** was added to `pom.xml` or `package.json`.
The workflow engine, scheduler, tool layer, planner, expression language and JSON-schema subset are plain Java on
the existing Spring Boot and PostgreSQL stack. Scheduling uses PostgreSQL row locks (`FOR UPDATE SKIP LOCKED`),
not a broker or workflow service. The planner and the built-in executor are rule-based and deterministic; no
model, embedding service or API key is required at runtime. The optional `external` executor lets an agent you
already run elsewhere (a person, a script, or an AI coding tool such as Claude Code with its own subscription) do
a step through its inbox; Memora never calls it. Test-only additions: none to the dependency list (Playwright and
Vitest were already present).

## Not used, deliberately

No LLM or embedding API, no vector database, no Redis, Kafka or Kubernetes, no analytics or error-tracking
SaaS, no paid fonts or UI kits (the dashboard uses system fonts via a CSS font stack).
