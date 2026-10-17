-- Memora 2.0: configurable agents, versioned workflows and a database-backed execution engine.
-- Every table is tenant-owned, references other rows through (tenant_id, id) and is covered by
-- the same FORCE ROW LEVEL SECURITY policy as the V1 tables.

CREATE TABLE agent_definitions (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    def_key           varchar(64)  NOT NULL,
    name              varchar(100) NOT NULL,
    description       text,
    role              varchar(100) NOT NULL,
    instructions      text         NOT NULL DEFAULT '',
    capabilities      text[]       NOT NULL DEFAULT '{}',
    tools             text[]       NOT NULL DEFAULT '{}',
    input_schema      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    output_schema     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    memory_policy     jsonb        NOT NULL DEFAULT '{}'::jsonb,
    context_policy    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    executor          varchar(40)  NOT NULL,
    behavior          jsonb        NOT NULL DEFAULT '{}'::jsonb,
    timeout_seconds   integer      NOT NULL DEFAULT 60 CHECK (timeout_seconds BETWEEN 1 AND 86400),
    max_attempts      integer      NOT NULL DEFAULT 3 CHECK (max_attempts BETWEEN 1 AND 10),
    backoff_seconds   integer      NOT NULL DEFAULT 2 CHECK (backoff_seconds BETWEEN 0 AND 3600),
    status            varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version           integer      NOT NULL DEFAULT 1,
    lock_version      bigint       NOT NULL DEFAULT 0,
    source_template   varchar(64),
    agent_id          uuid         NOT NULL,
    created_by_user_id uuid REFERENCES users(id),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, def_key),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES agents(tenant_id, id)
);
CREATE INDEX idx_agent_definitions_tenant ON agent_definitions(tenant_id, status, name);

CREATE TABLE agent_definition_versions (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    definition_id  uuid NOT NULL,
    version        integer     NOT NULL,
    snapshot       jsonb       NOT NULL,
    created_by_user_id uuid REFERENCES users(id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (definition_id, version),
    FOREIGN KEY (tenant_id, definition_id) REFERENCES agent_definitions(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE workflow_definitions (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id       uuid,
    wf_key           varchar(64)  NOT NULL,
    name             varchar(200) NOT NULL,
    description      text,
    current_version  integer      NOT NULL DEFAULT 1,
    status           varchar(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    lock_version     bigint       NOT NULL DEFAULT 0,
    created_by_user_id uuid REFERENCES users(id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, wf_key),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id)
);

CREATE TABLE workflow_definition_versions (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    workflow_id   uuid NOT NULL,
    version       integer     NOT NULL,
    spec          jsonb       NOT NULL,
    change_note   varchar(500),
    created_by_user_id uuid REFERENCES users(id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workflow_id, version),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, workflow_id) REFERENCES workflow_definitions(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE workflow_runs (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id        uuid NOT NULL,
    workflow_id       uuid NOT NULL,
    workflow_version  integer      NOT NULL,
    name              varchar(300) NOT NULL,
    goal              text,
    inputs            jsonb        NOT NULL DEFAULT '{}'::jsonb,
    spec              jsonb        NOT NULL,
    agents            jsonb        NOT NULL,
    options           jsonb        NOT NULL DEFAULT '{}'::jsonb,
    status            varchar(20)  NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'PAUSED', 'WAITING_APPROVAL',
                                                              'BLOCKED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    completion        jsonb,
    outputs           jsonb,
    task_id           uuid,
    started_by_user_id uuid REFERENCES users(id),
    lock_version      bigint       NOT NULL DEFAULT 0,
    idempotency_key   varchar(100),
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id),
    FOREIGN KEY (tenant_id, workflow_id) REFERENCES workflow_definitions(tenant_id, id),
    FOREIGN KEY (tenant_id, task_id) REFERENCES tasks(tenant_id, id) ON DELETE SET NULL (task_id)
);
CREATE INDEX idx_workflow_runs_tenant ON workflow_runs(tenant_id, created_at DESC);
CREATE INDEX idx_workflow_runs_project ON workflow_runs(tenant_id, project_id, created_at DESC);
CREATE INDEX idx_workflow_runs_active ON workflow_runs(status) WHERE status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL');

CREATE TABLE workflow_step_runs (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_id             uuid NOT NULL,
    step_key           varchar(64)  NOT NULL,
    iteration          integer      NOT NULL DEFAULT 1,
    name               varchar(200) NOT NULL,
    agent_definition_id uuid,
    agent_key          varchar(64),
    agent_version      integer,
    executor           varchar(40),
    status             varchar(20)  NOT NULL CHECK (status IN ('PENDING', 'READY', 'RUNNING', 'WAITING_APPROVAL',
                                                               'BLOCKED', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'CANCELLED')),
    depends_on         text[]       NOT NULL DEFAULT '{}',
    attempts           integer      NOT NULL DEFAULT 0,
    max_attempts       integer      NOT NULL DEFAULT 3,
    input              jsonb,
    output             jsonb,
    error              text,
    error_kind         varchar(20),
    ready_reason       jsonb,
    lease_owner        varchar(100),
    lease_expires_at   timestamptz,
    next_attempt_at    timestamptz,
    task_id            uuid,
    handoff_id         uuid,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    ready_at           timestamptz,
    started_at         timestamptz,
    finished_at        timestamptz,
    lock_version       bigint       NOT NULL DEFAULT 0,
    UNIQUE (run_id, step_key, iteration),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_runs(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, agent_definition_id) REFERENCES agent_definitions(tenant_id, id),
    FOREIGN KEY (tenant_id, task_id) REFERENCES tasks(tenant_id, id) ON DELETE SET NULL (task_id),
    FOREIGN KEY (tenant_id, handoff_id) REFERENCES handoffs(tenant_id, id) ON DELETE SET NULL (handoff_id)
);
CREATE INDEX idx_step_runs_run ON workflow_step_runs(tenant_id, run_id, created_at);
-- The scheduler's two hot queries: claimable steps and expired leases.
CREATE INDEX idx_step_runs_ready ON workflow_step_runs(next_attempt_at, created_at) WHERE status = 'READY';
CREATE INDEX idx_step_runs_lease ON workflow_step_runs(lease_expires_at) WHERE status = 'RUNNING';

CREATE TABLE workflow_artifacts (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_id        uuid NOT NULL,
    step_run_id   uuid,
    project_id    uuid NOT NULL,
    name          varchar(200) NOT NULL,
    kind          varchar(20)  NOT NULL CHECK (kind IN ('markdown', 'json', 'text', 'code')),
    content       text         NOT NULL,
    checksum      varchar(64)  NOT NULL,
    size_bytes    integer      NOT NULL,
    version       integer      NOT NULL DEFAULT 1,
    created_by_agent_id uuid,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (run_id, name, version),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_runs(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, step_run_id) REFERENCES workflow_step_runs(tenant_id, id) ON DELETE SET NULL (step_run_id),
    FOREIGN KEY (tenant_id, project_id) REFERENCES projects(tenant_id, id)
);
CREATE INDEX idx_artifacts_run ON workflow_artifacts(tenant_id, run_id, name, version DESC);
CREATE INDEX idx_artifacts_project ON workflow_artifacts(tenant_id, project_id, created_at DESC);

CREATE TABLE workflow_events (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_id        uuid NOT NULL,
    step_run_id   uuid,
    event_type    varchar(40) NOT NULL,
    message       text,
    payload       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at    timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_runs(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_workflow_events_run ON workflow_events(tenant_id, run_id, created_at);

CREATE TABLE workflow_approvals (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_id        uuid NOT NULL,
    step_run_id   uuid NOT NULL,
    status        varchar(20) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reason        text,
    decided_by_user_id uuid REFERENCES users(id),
    decision_note text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    decided_at    timestamptz,
    UNIQUE (tenant_id, id),
    UNIQUE (step_run_id),
    FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_runs(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, step_run_id) REFERENCES workflow_step_runs(tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE tool_invocations (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    run_id        uuid,
    step_run_id   uuid,
    agent_definition_id uuid,
    tool_id       varchar(60)  NOT NULL,
    status        varchar(20)  NOT NULL CHECK (status IN ('SUCCEEDED', 'FAILED', 'DENIED')),
    side_effects  boolean      NOT NULL,
    input_summary jsonb        NOT NULL DEFAULT '{}'::jsonb,
    output_summary jsonb       NOT NULL DEFAULT '{}'::jsonb,
    error         text,
    duration_ms   integer      NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, run_id) REFERENCES workflow_runs(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_tool_invocations_step ON tool_invocations(tenant_id, step_run_id, created_at);

-- Memory gains workflow- and run-level scopes. Scope keys stay strings ('workflow:<id>', 'run:<id>',
-- 'agent:<id>'); these columns make the scopes filterable and tenant-checked.
ALTER TABLE memories ADD COLUMN workflow_id uuid;
ALTER TABLE memories ADD COLUMN workflow_run_id uuid;
ALTER TABLE memories ADD CONSTRAINT fk_memories_workflow
    FOREIGN KEY (tenant_id, workflow_id) REFERENCES workflow_definitions(tenant_id, id);
ALTER TABLE memories ADD CONSTRAINT fk_memories_workflow_run
    FOREIGN KEY (tenant_id, workflow_run_id) REFERENCES workflow_runs(tenant_id, id) ON DELETE SET NULL (workflow_run_id);
CREATE INDEX idx_memories_tenant_run ON memories(tenant_id, workflow_run_id) WHERE workflow_run_id IS NOT NULL;
CREATE INDEX idx_memories_tenant_scope ON memories(tenant_id, scope_key, created_at DESC);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['agent_definitions', 'agent_definition_versions', 'workflow_definitions',
                             'workflow_definition_versions', 'workflow_runs', 'workflow_step_runs',
                             'workflow_artifacts', 'workflow_events', 'workflow_approvals', 'tool_invocations']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (memora_rls_bypass() OR tenant_id = memora_current_tenant()) '
            'WITH CHECK (memora_rls_bypass() OR tenant_id = memora_current_tenant())', t);
    END LOOP;
END
$$;
