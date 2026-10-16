-- Defense in depth: PostgreSQL row-level security on every tenant-owned table.
--
-- The application sets two session settings each time it borrows a pooled connection:
--   app.tenant_id   the tenant of the authenticated principal (empty when there is none)
--   app.rls_bypass  'on' only for system work with no principal (login lookup, seeding,
--                   lease recovery); 'off' for every authenticated request.
--                   Unset means 'off', so ad-hoc sessions see nothing until they opt in.
-- FORCE makes the policies apply to the table owner too, which is the role the app uses.

CREATE FUNCTION memora_current_tenant() RETURNS uuid
    LANGUAGE sql STABLE AS
$$ SELECT nullif(current_setting('app.tenant_id', true), '')::uuid $$;

CREATE FUNCTION memora_rls_bypass() RETURNS boolean
    LANGUAGE sql STABLE AS
$$ SELECT coalesce(current_setting('app.rls_bypass', true), 'off') = 'on' $$;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['memberships', 'projects', 'agents', 'conversations', 'messages',
                             'tasks', 'task_events', 'memories', 'handoffs', 'audit_events', 'lab_runs']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (memora_rls_bypass() OR tenant_id = memora_current_tenant()) '
            'WITH CHECK (memora_rls_bypass() OR tenant_id = memora_current_tenant())', t);
    END LOOP;
END
$$;
