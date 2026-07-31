-- V010 granted the runtime role SELECT, INSERT and UPDATE and never granted DELETE, but four
-- administration commands replace authority by clearing the previous rows first. Under the
-- least-privilege runtime role those commands failed with permission denied, so role assignment,
-- permission override replacement, role permission editing and allowlist removal were impossible
-- in any deployment that used the separate runtime role. The controller tests did not see it
-- because they connect as the migration owner.
--
-- DELETE stays limited to exactly the four relationship tables the application clears. Identities,
-- roles, sessions and every append-only history table keep their existing no-delete posture.

DO $$
DECLARE
    v_runtime_role text := current_setting('erp.runtime_role', true);
BEGIN
    IF v_runtime_role IS NULL OR btrim(v_runtime_role) = '' THEN
        RAISE NOTICE 'erp.runtime_role is not configured; runtime delete grants were not created';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_runtime_role) THEN
        RAISE EXCEPTION 'Configured ERP runtime role % does not exist', v_runtime_role
            USING ERRCODE = '42704';
    END IF;

    EXECUTE format(
        'GRANT DELETE ON identity.user_role, identity.user_permission_override,'
        || ' identity.role_permission, identity.ip_allowlist_entry TO %I',
        v_runtime_role
    );
END
$$;
