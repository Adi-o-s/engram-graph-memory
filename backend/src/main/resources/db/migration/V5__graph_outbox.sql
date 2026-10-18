-- Transactional outbox that feeds the FalkorDB memory graph.
--
-- Every write to a graph-relevant table adds a row here in the SAME transaction (row triggers below),
-- so a committed memory, task or handoff can never be missing from the graph for good: the
-- GraphProjector claims pending rows with SELECT ... FOR UPDATE SKIP LOCKED, re-reads the entity's
-- current state and MERGEs it into the tenant's graph. Projection is idempotent, so a replayed row
-- is harmless. Agent reads (who read which memory) are not row changes; the application inserts
-- those rows itself (kind READ) inside the request transaction.

CREATE TABLE graph_outbox (
    id               bigserial PRIMARY KEY,
    tenant_id        uuid        NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    kind             varchar(20) NOT NULL,
    entity_id        uuid,
    payload          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status           varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts         integer     NOT NULL DEFAULT 0,
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT clock_timestamp(),
    next_attempt_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    processed_at     timestamptz
);
CREATE INDEX idx_graph_outbox_pending ON graph_outbox(next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_graph_outbox_tenant_pending ON graph_outbox(tenant_id, id) WHERE status = 'PENDING';

ALTER TABLE graph_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE graph_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON graph_outbox USING (memora_rls_bypass() OR tenant_id = memora_current_tenant())
    WITH CHECK (memora_rls_bypass() OR tenant_id = memora_current_tenant());

CREATE FUNCTION memora_graph_outbox_emit() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO graph_outbox (tenant_id, kind, entity_id) VALUES (NEW.tenant_id, TG_ARGV[0], NEW.id);
    RETURN NULL;
END
$$;

-- Memories: the access counters change on every read; those updates are not graph changes
-- (reads are recorded as READ rows instead), so they do not emit.
CREATE TRIGGER trg_graph_memories
    AFTER INSERT OR UPDATE OF status, superseded_by_id, importance, confidence, tags, category, expires_at ON memories
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('MEMORY');
CREATE TRIGGER trg_graph_tasks AFTER INSERT OR UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('TASK');
CREATE TRIGGER trg_graph_handoffs AFTER INSERT OR UPDATE ON handoffs
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('HANDOFF');
CREATE TRIGGER trg_graph_messages AFTER INSERT ON messages
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('MESSAGE');
CREATE TRIGGER trg_graph_conversations AFTER INSERT ON conversations
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('CONVERSATION');
CREATE TRIGGER trg_graph_projects AFTER INSERT OR UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('PROJECT');
CREATE TRIGGER trg_graph_agents AFTER INSERT ON agents
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('AGENT');
CREATE TRIGGER trg_graph_runs AFTER INSERT OR UPDATE OF status ON workflow_runs
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('RUN');
CREATE TRIGGER trg_graph_steps AFTER INSERT OR UPDATE OF status ON workflow_step_runs
    FOR EACH ROW EXECUTE FUNCTION memora_graph_outbox_emit('STEP');
