-- Memora core schema. Every tenant-owned table carries tenant_id and is indexed by it.

CREATE TABLE tenants (
    id          uuid PRIMARY KEY,
    slug        varchar(64)  NOT NULL UNIQUE,
    name        varchar(200) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id             uuid PRIMARY KEY,
    email          varchar(320) NOT NULL UNIQUE,
    display_name   varchar(200) NOT NULL,
    password_hash  varchar(100) NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        varchar(20) NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, user_id)
);
CREATE INDEX idx_memberships_user ON memberships(user_id);

CREATE TABLE projects (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    slug         varchar(64)  NOT NULL,
    name         varchar(200) NOT NULL,
    description  text,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, slug),
    UNIQUE (tenant_id, id)
);

CREATE TABLE agents (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name               varchar(100) NOT NULL,
    role               varchar(30)  NOT NULL CHECK (role IN ('PLANNER', 'DEVELOPER', 'TESTER', 'CUSTOM')),
    description        text,
    capabilities       text[]       NOT NULL DEFAULT '{}',
    api_key_hash       varchar(64)  UNIQUE,
    api_key_prefix     varchar(16),
    status             varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    handoffs_received  bigint       NOT NULL DEFAULT 0,
    handoffs_sent      bigint       NOT NULL DEFAULT 0,
    last_seen_at       timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, name),
    UNIQUE (tenant_id, id)
);

CREATE TABLE conversations (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id       uuid,
    owner_user_id    uuid REFERENCES users(id),
    title            varchar(300) NOT NULL,
    session_id       varchar(100),
    summary          text,
    message_count    bigint      NOT NULL DEFAULT 0,
    idempotency_key  varchar(100),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    last_message_at  timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id)
);
CREATE INDEX idx_conversations_tenant_project ON conversations(tenant_id, project_id, updated_at DESC);
CREATE INDEX idx_conversations_tenant_owner ON conversations(tenant_id, owner_user_id, updated_at DESC);

CREATE TABLE messages (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    conversation_id  uuid NOT NULL,
    seq              bigint      NOT NULL,
    role             varchar(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'AGENT', 'SYSTEM')),
    author_user_id   uuid REFERENCES users(id),
    author_agent_id  uuid,
    content          text        NOT NULL,
    metadata         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key  varchar(100),
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, seq),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, author_agent_id) REFERENCES agents(tenant_id, id)
);
CREATE INDEX idx_messages_tenant_conversation_seq ON messages(tenant_id, conversation_id, seq);

CREATE TABLE tasks (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id         uuid,
    title              varchar(300) NOT NULL,
    description        text,
    status             varchar(20)  NOT NULL CHECK (status IN ('TODO', 'IN_PROGRESS', 'BLOCKED', 'IN_REVIEW', 'DONE', 'FAILED')),
    priority           integer      NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    assignee_agent_id  uuid,
    created_by_user_id uuid REFERENCES users(id),
    created_by_agent_id uuid,
    state              jsonb        NOT NULL DEFAULT '{}'::jsonb,
    version            bigint       NOT NULL DEFAULT 0,
    idempotency_key    varchar(100),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id),
    FOREIGN KEY (tenant_id, assignee_agent_id) REFERENCES agents(tenant_id, id),
    FOREIGN KEY (tenant_id, created_by_agent_id) REFERENCES agents(tenant_id, id)
);
CREATE INDEX idx_tasks_tenant_status ON tasks(tenant_id, status, updated_at DESC);
CREATE INDEX idx_tasks_tenant_project ON tasks(tenant_id, project_id, updated_at DESC);
CREATE INDEX idx_tasks_tenant_assignee ON tasks(tenant_id, assignee_agent_id);

CREATE TABLE task_events (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    task_id       uuid NOT NULL,
    event_type    varchar(40) NOT NULL,
    actor_type    varchar(10) NOT NULL CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),
    actor_id      uuid,
    from_status   varchar(20),
    to_status     varchar(20),
    task_version  bigint,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, task_id) REFERENCES tasks(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_task_events_tenant_task ON task_events(tenant_id, task_id, created_at);

CREATE TABLE memories (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id            uuid REFERENCES users(id),
    project_id         uuid,
    conversation_id    uuid,
    task_id            uuid,
    agent_id           uuid,
    memory_type        varchar(20)  NOT NULL CHECK (memory_type IN ('EPISODIC', 'SEMANTIC', 'PROCEDURAL')),
    category           varchar(40),
    content            text         NOT NULL,
    subject            varchar(200),
    predicate          varchar(200),
    object_value       varchar(500),
    fact_key           varchar(400),
    scope_key          varchar(100) NOT NULL,
    search_text        text         NOT NULL,
    search_vector      tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig, search_text)) STORED,
    tags               text[]       NOT NULL DEFAULT '{}',
    importance         integer      NOT NULL DEFAULT 3 CHECK (importance BETWEEN 1 AND 5),
    confidence         double precision NOT NULL DEFAULT 1.0 CHECK (confidence >= 0 AND confidence <= 1),
    status             varchar(20)  NOT NULL DEFAULT 'CURRENT' CHECK (status IN ('CURRENT', 'SUPERSEDED', 'RETRACTED')),
    version            integer      NOT NULL DEFAULT 1,
    lock_version       bigint       NOT NULL DEFAULT 0,
    supersedes_id      uuid REFERENCES memories(id),
    superseded_by_id   uuid REFERENCES memories(id),
    source_type        varchar(20)  NOT NULL CHECK (source_type IN ('MESSAGE', 'API', 'AGENT', 'HANDOFF', 'TASK', 'SYSTEM')),
    source_message_id  uuid,
    source_ref         varchar(500),
    extractor          varchar(100),
    created_by_user_id uuid REFERENCES users(id),
    created_by_agent_id uuid,
    access_count       bigint       NOT NULL DEFAULT 0,
    last_accessed_at   timestamptz,
    expires_at         timestamptz,
    metadata           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    idempotency_key    varchar(100),
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations(tenant_id, id) ON DELETE SET NULL (conversation_id),
    FOREIGN KEY (tenant_id, task_id) REFERENCES tasks(tenant_id, id) ON DELETE SET NULL (task_id),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES agents(tenant_id, id),
    FOREIGN KEY (tenant_id, source_message_id) REFERENCES messages(tenant_id, id) ON DELETE SET NULL (source_message_id)
);
-- At most one CURRENT fact per (tenant, scope, subject+property). This makes concurrent
-- writers of the same fact serialize instead of producing two "current" answers.
CREATE UNIQUE INDEX uq_memories_current_fact ON memories(tenant_id, scope_key, fact_key)
    WHERE status = 'CURRENT' AND fact_key IS NOT NULL;
CREATE INDEX idx_memories_tenant_type_status ON memories(tenant_id, memory_type, status, created_at DESC);
CREATE INDEX idx_memories_tenant_project ON memories(tenant_id, project_id, created_at DESC);
CREATE INDEX idx_memories_tenant_conversation ON memories(tenant_id, conversation_id);
CREATE INDEX idx_memories_tenant_task ON memories(tenant_id, task_id);
CREATE INDEX idx_memories_tenant_user ON memories(tenant_id, user_id);
CREATE INDEX idx_memories_fact_history ON memories(tenant_id, scope_key, fact_key, version);
CREATE INDEX idx_memories_search ON memories USING gin(search_vector);
CREATE INDEX idx_memories_tags ON memories USING gin(tags);

CREATE TABLE handoffs (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    task_id           uuid NOT NULL,
    from_agent_id     uuid NOT NULL,
    to_agent_id       uuid NOT NULL,
    summary           text        NOT NULL,
    memory_refs       uuid[]      NOT NULL DEFAULT '{}',
    decisions         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    artifacts         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    next_action       text        NOT NULL,
    status            varchar(20) NOT NULL CHECK (status IN ('PENDING', 'ACKNOWLEDGED', 'COMPLETED', 'FAILED', 'REJECTED', 'DEAD_LETTER')),
    task_version      bigint      NOT NULL,
    attempts          integer     NOT NULL DEFAULT 0,
    max_attempts      integer     NOT NULL DEFAULT 3,
    failure_reason    text,
    result            jsonb,
    lease_expires_at  timestamptz,
    claimed_by_agent_id uuid,
    version           bigint      NOT NULL DEFAULT 0,
    idempotency_key   varchar(100),
    created_at        timestamptz NOT NULL DEFAULT now(),
    acknowledged_at   timestamptz,
    completed_at      timestamptz,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, task_id) REFERENCES tasks(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, from_agent_id) REFERENCES agents(tenant_id, id),
    FOREIGN KEY (tenant_id, to_agent_id) REFERENCES agents(tenant_id, id)
);
CREATE INDEX idx_handoffs_inbox ON handoffs(tenant_id, to_agent_id, status, created_at);
CREATE INDEX idx_handoffs_task ON handoffs(tenant_id, task_id, created_at);
CREATE INDEX idx_handoffs_lease ON handoffs(status, lease_expires_at) WHERE status = 'ACKNOWLEDGED';
-- One active (not yet finished) handoff per task: a task is owned by exactly one agent at a time.
CREATE UNIQUE INDEX uq_handoffs_active_per_task ON handoffs(tenant_id, task_id)
    WHERE status IN ('PENDING', 'ACKNOWLEDGED');

CREATE TABLE audit_events (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    actor_type      varchar(10)  NOT NULL CHECK (actor_type IN ('USER', 'AGENT', 'SYSTEM')),
    actor_id        uuid,
    actor_name      varchar(200),
    action          varchar(60)  NOT NULL,
    entity_type     varchar(40)  NOT NULL,
    entity_id       uuid,
    details         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  varchar(64),
    created_at      timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_tenant_time ON audit_events(tenant_id, created_at DESC);
CREATE INDEX idx_audit_tenant_entity ON audit_events(tenant_id, entity_type, entity_id);

CREATE TABLE lab_runs (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    scenario        varchar(30) NOT NULL,
    threads         integer     NOT NULL,
    operations      integer     NOT NULL,
    results         jsonb       NOT NULL,
    created_by_user_id uuid REFERENCES users(id),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_lab_runs_tenant_time ON lab_runs(tenant_id, created_at DESC);
