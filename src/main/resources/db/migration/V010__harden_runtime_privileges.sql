-- Deployment must create the runtime role before Flyway runs when database-level
-- privilege separation is required. Set `erp.runtime_role` to that role name.
-- Without the setting, this migration still removes PUBLIC access; the owner/migration
-- role retains control and deployment can grant privileges explicitly afterward.

REVOKE ALL ON SCHEMA system, identity, audit, master_data, sales, production, inventory, delivery FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA system, identity, audit, master_data, sales, production, inventory, delivery FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA system, identity, audit, master_data, sales, production, inventory, delivery FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA system, identity, audit, master_data, sales, production, inventory, delivery FROM PUBLIC;

-- The allocator is the only function exposed to the runtime role. SECURITY DEFINER
-- keeps direct counter-table writes unavailable to that role.
ALTER FUNCTION system.next_document_number(varchar, varchar, smallint) SECURITY DEFINER;
ALTER FUNCTION system.next_document_number(varchar, varchar, smallint)
    SET search_path = pg_catalog, system;

ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

DO $$
DECLARE
    v_runtime_role text := current_setting('erp.runtime_role', true);
BEGIN
    IF v_runtime_role IS NULL OR btrim(v_runtime_role) = '' THEN
        RAISE NOTICE 'erp.runtime_role is not configured; runtime grants were not created';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_runtime_role) THEN
        RAISE EXCEPTION 'Configured ERP runtime role % does not exist', v_runtime_role
            USING ERRCODE = '42704';
    END IF;

    EXECUTE format(
        'GRANT USAGE ON SCHEMA identity, master_data, sales, production, inventory, delivery TO %I',
        v_runtime_role
    );

    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA identity, master_data, sales, production, inventory, delivery TO %I',
        v_runtime_role
    );

    EXECUTE format(
        'REVOKE UPDATE, DELETE ON identity.login_event, production.production_event, inventory.stock_movement, delivery.delivery_event FROM %I',
        v_runtime_role
    );

    EXECUTE format(
        'GRANT USAGE ON SCHEMA audit TO %I',
        v_runtime_role
    );
    EXECUTE format(
        'GRANT SELECT, INSERT ON audit.audit_event TO %I',
        v_runtime_role
    );

    EXECUTE format(
        'GRANT USAGE ON SCHEMA system TO %I',
        v_runtime_role
    );
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON system.idempotency_record TO %I',
        v_runtime_role
    );
    EXECUTE format(
        'GRANT EXECUTE ON FUNCTION system.next_document_number(varchar, varchar, smallint) TO %I',
        v_runtime_role
    );
END;
$$;
