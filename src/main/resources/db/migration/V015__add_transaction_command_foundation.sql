-- Transaction command foundation. V001-V014 are immutable; every change in this file is forward-only.

-- Fail fast before tightening Production history. Operators must investigate rather than rewrite history.
LOCK TABLE production.production_order IN SHARE ROW EXCLUSIVE MODE NOWAIT;

DO $$
DECLARE
    v_invalid_count bigint;
    v_invalid_ids text;
BEGIN
    SELECT count(*), string_agg(id::text, ',' ORDER BY id)
    INTO v_invalid_count, v_invalid_ids
    FROM production.production_order
    WHERE status = 'FINISHED'
      AND produced_qty IS DISTINCT FROM planned_qty;

    IF v_invalid_count > 0 THEN
        RAISE EXCEPTION 'TRANSACTION_MIGRATION_INVALID: % FINISHED Production Orders have produced quantity different from planned quantity; ids=%',
            v_invalid_count, left(v_invalid_ids, 2000)
            USING ERRCODE = '23514';
    END IF;
END
$$;

-- Durable idempotency keeps only digests and bounded public responses. Raw request and credential
-- material are deliberately absent.
ALTER TABLE system.idempotency_record
    ALTER COLUMN scope TYPE varchar(128),
    ADD COLUMN scope_digest bytea,
    ADD COLUMN actor_user_id uuid,
    ADD COLUMN request_method varchar(10),
    ADD COLUMN normalized_path varchar(500),
    ADD COLUMN command_id uuid,
    ADD COLUMN response_status smallint,
    ADD COLUMN response_content_type varchar(120),
    ADD COLUMN response_body bytea,
    ADD COLUMN completed_at timestamptz,
    ADD COLUMN lease_owner uuid,
    ADD COLUMN lease_generation bigint NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at timestamptz;

ALTER TABLE system.idempotency_record
    DROP CONSTRAINT ck_idempotency_status,
    ADD CONSTRAINT fk_idempotency_actor FOREIGN KEY (actor_user_id) REFERENCES identity.app_user (id),
    ADD CONSTRAINT ck_idempotency_scope_digest CHECK (
        scope_digest IS NULL OR octet_length(scope_digest) = 32
    ),
    ADD CONSTRAINT ck_idempotency_method CHECK (
        request_method IS NULL OR request_method IN ('POST', 'PUT', 'PATCH', 'DELETE')
    ),
    ADD CONSTRAINT ck_idempotency_path CHECK (
        normalized_path IS NULL OR (btrim(normalized_path) <> '' AND left(normalized_path, 1) = '/')
    ),
    ADD CONSTRAINT ck_idempotency_request_hash CHECK (btrim(request_hash) <> ''),
    ADD CONSTRAINT ck_idempotency_response_size CHECK (
        response_body IS NULL OR octet_length(response_body) <= 262144
    ),
    ADD CONSTRAINT ck_idempotency_response_status CHECK (
        response_status IS NULL OR response_status BETWEEN 200 AND 599
    ),
    ADD CONSTRAINT ck_idempotency_lease_generation CHECK (lease_generation >= 0);

-- Existing V014 rows cannot be replayed because they have no response bytes. Do not fabricate one.
-- Convert them before adding the new status and lifecycle checks so populated upgrades stay valid.
UPDATE system.idempotency_record
SET status = CASE WHEN status = 'COMPLETED' THEN 'QUARANTINED' ELSE 'RETRYABLE' END,
    expires_at = CASE WHEN status = 'COMPLETED' THEN expires_at ELSE clock_timestamp() END,
    lease_owner = NULL,
    lease_expires_at = NULL,
    completed_at = NULL,
    response_status = NULL,
    response_content_type = NULL,
    response_body = NULL;

ALTER TABLE system.idempotency_record
    ADD CONSTRAINT ck_idempotency_status CHECK (
        status IN ('IN_PROGRESS', 'COMPLETED', 'RETRYABLE', 'QUARANTINED')
    ),
    ADD CONSTRAINT ck_idempotency_lifecycle CHECK (
        (status = 'IN_PROGRESS'
            AND lease_owner IS NOT NULL
            AND lease_expires_at IS NOT NULL
            AND completed_at IS NULL
            AND response_status IS NULL
            AND response_content_type IS NULL
            AND response_body IS NULL)
        OR (status = 'COMPLETED'
            AND completed_at IS NOT NULL
            AND response_status IS NOT NULL
            AND response_content_type IS NOT NULL
            AND response_body IS NOT NULL
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL)
        OR (status = 'RETRYABLE'
            AND completed_at IS NULL
            AND response_status IS NULL
            AND response_content_type IS NULL
            AND response_body IS NULL
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL)
        OR (status = 'QUARANTINED'
            AND completed_at IS NULL
            AND response_status IS NULL
            AND response_content_type IS NULL
            AND response_body IS NULL
            AND lease_owner IS NULL
            AND lease_expires_at IS NULL)
    );

CREATE UNIQUE INDEX uq_idempotency_scope_digest_key
    ON system.idempotency_record (scope_digest, idempotency_key)
    WHERE scope_digest IS NOT NULL;
CREATE INDEX ix_idempotency_active_lease
    ON system.idempotency_record (lease_expires_at)
    WHERE status = 'IN_PROGRESS';
CREATE INDEX ix_idempotency_terminal_expiry
    ON system.idempotency_record (expires_at, id)
    WHERE status IN ('COMPLETED', 'QUARANTINED', 'RETRYABLE');

-- Buyer Order revisions preserve confirmed line and Production history.
ALTER TABLE sales.buyer_order
    ADD COLUMN active_revision integer NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_buyer_order_active_revision CHECK (active_revision > 0);

ALTER TABLE sales.buyer_order_item
    DROP CONSTRAINT uq_buyer_order_item_line,
    ADD COLUMN revision integer NOT NULL DEFAULT 1,
    ADD COLUMN active_revision boolean NOT NULL DEFAULT true,
    ADD COLUMN status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT uq_buyer_order_item_revision_line UNIQUE (buyer_order_id, revision, line_no),
    ADD CONSTRAINT ck_buyer_order_item_revision CHECK (revision > 0),
    ADD CONSTRAINT ck_buyer_order_item_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    ADD CONSTRAINT ck_buyer_order_item_active_status CHECK (
        (active_revision AND status = 'ACTIVE') OR (NOT active_revision AND status = 'CANCELLED')
    );

CREATE UNIQUE INDEX uq_buyer_order_item_active_line
    ON sales.buyer_order_item (buyer_order_id, line_no)
    WHERE active_revision;

-- A superseded item is identified by its own revision against the order's active revision, not by the
-- order's momentary status. Reopen writes the header and its items in one transaction, and a handler
-- that advances the header first must not thereby unlock the very rows it is about to retire.
CREATE OR REPLACE FUNCTION sales.protect_confirmed_buyer_order_item()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(20);
    v_active_revision integer;
    v_editable boolean;
BEGIN
    SELECT status, active_revision
    INTO v_status, v_active_revision
    FROM sales.buyer_order
    WHERE id = COALESCE(NEW.buyer_order_id, OLD.buyer_order_id);

    IF TG_OP = 'DELETE' THEN
        IF v_status <> 'STANDBY'
           OR NOT OLD.active_revision
           OR OLD.status <> 'ACTIVE'
           OR OLD.revision <> v_active_revision THEN
            RAISE EXCEPTION 'Only items of the active STANDBY revision may be deleted'
                USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF v_status <> 'STANDBY'
           OR NOT NEW.active_revision
           OR NEW.status <> 'ACTIVE'
           OR NEW.revision <> v_active_revision THEN
            RAISE EXCEPTION 'Only items of the active STANDBY revision may be inserted'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status = 'CANCELLED' OR NOT OLD.active_revision THEN
        RAISE EXCEPTION 'Retired Buyer Order items are immutable'
            USING ERRCODE = '55000';
    END IF;

    v_editable := v_status = 'STANDBY' AND OLD.revision = v_active_revision;

    IF NOT v_editable THEN
        IF NEW.status <> 'CANCELLED'
           OR NEW.active_revision
           OR (to_jsonb(NEW) - ARRAY['status', 'active_revision', 'updated_at', 'updated_by'])
              IS DISTINCT FROM
              (to_jsonb(OLD) - ARRAY['status', 'active_revision', 'updated_at', 'updated_by']) THEN
            RAISE EXCEPTION 'Confirmed or retired Buyer Order items may only be retired by reopen'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

-- Reopen must move to a new revision in the same statement, otherwise the rule above has nothing to
-- distinguish the history it is meant to protect.
CREATE OR REPLACE FUNCTION sales.guard_buyer_order_reopen()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CONFIRMED' AND NEW.status = 'STANDBY' THEN
        IF NEW.active_revision <= OLD.active_revision THEN
            RAISE EXCEPTION 'Reopening a Buyer Order must advance its active revision'
                USING ERRCODE = '23514';
        END IF;

        IF EXISTS (
            SELECT 1
            FROM production.production_order po
            LEFT JOIN inventory.stock_position sp ON sp.production_order_id = po.id
            LEFT JOIN inventory.stock_movement sm ON sm.stock_position_id = sp.id
            LEFT JOIN delivery.delivery_note_item dni ON dni.production_order_id = po.id
            WHERE po.buyer_order_id = OLD.id
              AND (
                  po.status = 'FINISHED'
                  OR po.production_group_id IS NOT NULL
                  OR sp.id IS NOT NULL
                  OR sm.id IS NOT NULL
                  OR dni.id IS NOT NULL
              )
        ) THEN
            RAISE EXCEPTION 'Buyer Order cannot be reopened after Production grouping/finish, Stock, or Delivery exists'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

-- Production cancellation preserves configuration and event history.
ALTER TABLE production.production_order
    DROP CONSTRAINT ck_production_order_status,
    DROP CONSTRAINT ck_production_order_finish_state,
    ADD CONSTRAINT ck_production_order_status CHECK (status IN ('OPEN', 'FINISHED', 'CANCELLED')),
    ADD CONSTRAINT ck_production_order_finish_state CHECK (
        (status IN ('OPEN', 'CANCELLED') AND produced_qty IS NULL AND finished_at IS NULL AND finished_by IS NULL)
        OR (status = 'FINISHED' AND produced_qty = planned_qty AND finished_at IS NOT NULL AND finished_by IS NOT NULL)
    ),
    ADD CONSTRAINT ck_production_order_full_finish CHECK (
        status <> 'FINISHED' OR produced_qty = planned_qty
    );

ALTER TABLE production.production_event
    DROP CONSTRAINT ck_production_event_type,
    ADD CONSTRAINT ck_production_event_type CHECK (
        event_type IN ('CREATED', 'GROUPED', 'UNGROUPED', 'CONFIGURED', 'FINISHED', 'CANCELLED')
    );

CREATE OR REPLACE FUNCTION production.protect_finished_order()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('FINISHED', 'CANCELLED')
       AND (to_jsonb(NEW) - ARRAY['updated_at', 'updated_by', 'version'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['updated_at', 'updated_by', 'version']) THEN
        RAISE EXCEPTION 'Terminal Production Order is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.production_no IS DISTINCT FROM OLD.production_no
       OR NEW.buyer_order_item_id IS DISTINCT FROM OLD.buyer_order_item_id
       OR NEW.buyer_order_id IS DISTINCT FROM OLD.buyer_order_id
       OR NEW.product_kind_snapshot IS DISTINCT FROM OLD.product_kind_snapshot
       OR NEW.product_no IS DISTINCT FROM OLD.product_no
       OR NEW.qr_value IS DISTINCT FROM OLD.qr_value
       OR NEW.planned_qty IS DISTINCT FROM OLD.planned_qty THEN
        RAISE EXCEPTION 'Production source and identity fields are immutable'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'OPEN' AND NEW.status NOT IN ('OPEN', 'FINISHED', 'CANCELLED') THEN
        RAISE EXCEPTION 'Invalid Production Order transition'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;

-- Every Production configuration child is mutable only while its parent is OPEN. This keeps
-- FINISHED and CANCELLED configuration as historical evidence.
CREATE FUNCTION production.protect_configuration_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_order_id uuid;
    v_status varchar(20);
BEGIN
    v_order_id := COALESCE(NEW.production_order_id, OLD.production_order_id);
    SELECT status INTO v_status FROM production.production_order WHERE id = v_order_id;

    IF v_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'Production configuration is mutable only while the order is OPEN'
            USING ERRCODE = '55000';
    END IF;
    RETURN COALESCE(NEW, OLD);
END
$$;

CREATE TRIGGER trg_print_config_protect_history
BEFORE INSERT OR UPDATE OR DELETE ON production.production_print_config
FOR EACH ROW EXECUTE FUNCTION production.protect_configuration_history();

CREATE TRIGGER trg_woven_config_protect_history
BEFORE INSERT OR UPDATE OR DELETE ON production.production_woven_config
FOR EACH ROW EXECUTE FUNCTION production.protect_configuration_history();

CREATE TRIGGER trg_production_process_protect_history
BEFORE INSERT OR UPDATE OR DELETE ON production.production_order_process
FOR EACH ROW EXECUTE FUNCTION production.protect_configuration_history();

CREATE TRIGGER trg_woven_weave_type_protect_history
BEFORE INSERT OR UPDATE OR DELETE ON production.production_woven_weave_type
FOR EACH ROW EXECUTE FUNCTION production.protect_configuration_history();

CREATE TRIGGER trg_woven_yarn_line_protect_history
BEFORE INSERT OR UPDATE OR DELETE ON production.production_woven_yarn_line
FOR EACH ROW EXECUTE FUNCTION production.protect_configuration_history();

-- Reopen is history-preserving: all Production rows of retired items must already be CANCELLED.
CREATE OR REPLACE FUNCTION sales.validate_reopened_buyer_order()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'STANDBY' AND OLD.status = 'CONFIRMED' AND EXISTS (
        SELECT 1
        FROM production.production_order po
        JOIN sales.buyer_order_item boi ON boi.id = po.buyer_order_item_id
        WHERE po.buyer_order_id = NEW.id
          AND boi.revision < NEW.active_revision
          AND po.status <> 'CANCELLED'
    ) THEN
        RAISE EXCEPTION 'Reopening Buyer Order requires cancelling prior OPEN Production Orders in the same transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

-- Existing V009 catalog is authoritative. V015 grants all 18 transaction actions to SALE.
INSERT INTO identity.role_permission (role_id, permission_id, granted_by)
SELECT
    '20000000-0000-0000-0000-000000000001'::uuid,
    p.id,
    '00000000-0000-0000-0000-000000000001'::uuid
FROM identity.permission p
WHERE p.module_code IN ('BUYER_ORDER', 'PRODUCTION', 'DELIVERY', 'STOCK')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- All new helpers stay unavailable to PUBLIC. Runtime receives only the mutable-child deletes used
-- by application replacement commands and bounded cleanup of expired idempotency records.
REVOKE ALL ON FUNCTION production.protect_configuration_history() FROM PUBLIC;

DO $$
DECLARE
    v_runtime_role text := current_setting('erp.runtime_role', true);
BEGIN
    REVOKE ALL ON system.idempotency_record FROM PUBLIC;

    IF v_runtime_role IS NULL OR btrim(v_runtime_role) = '' THEN
        RAISE NOTICE 'erp.runtime_role is not configured; V015 runtime grants were not created';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_runtime_role) THEN
        RAISE EXCEPTION 'Configured ERP runtime role % does not exist', v_runtime_role
            USING ERRCODE = '42704';
    END IF;

    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON system.idempotency_record TO %I',
        v_runtime_role
    );
    EXECUTE format(
        'GRANT DELETE ON sales.buyer_order_item, production.production_group,'
        || ' production.production_print_config, production.production_order_process,'
        || ' production.production_woven_config, production.production_woven_weave_type,'
        || ' production.production_woven_yarn_line, delivery.delivery_note_item TO %I',
        v_runtime_role
    );
END
$$;
