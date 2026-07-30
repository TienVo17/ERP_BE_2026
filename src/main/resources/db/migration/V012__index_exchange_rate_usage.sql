-- Pre-V012 writers do not participate in advisory coordination. Acquire the complete
-- guarded-table fence with NOWAIT before validation. Any concurrent writer makes migration
-- fail fast and roll back; operators quiesce guarded writes and rerun the migration.
LOCK TABLE
    sales.buyer_order,
    inventory.stock_position,
    delivery.delivery_note,
    production.production_order_process,
    master_data.customer,
    master_data.process_master,
    master_data.monthly_exchange_rate
IN SHARE ROW EXCLUSIVE MODE NOWAIT;

CREATE INDEX ix_delivery_note_used_exchange_rate
    ON delivery.delivery_note (exchange_rate_id)
    WHERE status IN ('POSTED', 'REVERSED');

-- Refuse to install lifecycle guards over inconsistent V011 business data.
-- Migration intentionally does not rewrite or archive any business relationship.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM master_data.customer c
        WHERE c.status = 'ARCHIVED'
          AND (
              EXISTS (SELECT 1 FROM sales.buyer_order bo WHERE bo.customer_id = c.id)
              OR EXISTS (SELECT 1 FROM inventory.stock_position sp WHERE sp.customer_id = c.id)
              OR EXISTS (SELECT 1 FROM delivery.delivery_note dn WHERE dn.customer_id = c.id)
          )
    ) THEN
        RAISE EXCEPTION 'MASTER_GUARD_MIGRATION_INVALID: business data references an ARCHIVED Customer'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM master_data.process_master p
        WHERE p.status = 'ARCHIVED'
          AND EXISTS (
              SELECT 1 FROM production.production_order_process pop WHERE pop.process_id = p.id
          )
    ) THEN
        RAISE EXCEPTION 'MASTER_GUARD_MIGRATION_INVALID: business data references an ARCHIVED Process'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM master_data.monthly_exchange_rate r
        WHERE r.status = 'ARCHIVED'
          AND EXISTS (
              SELECT 1
              FROM delivery.delivery_note dn
              WHERE dn.exchange_rate_id = r.id
                AND dn.status IN ('POSTED', 'REVERSED')
          )
    ) THEN
        RAISE EXCEPTION 'MASTER_GUARD_MIGRATION_INVALID: posted or reversed Delivery references an ARCHIVED Exchange Rate'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

-- One transaction-scoped advisory lock coordinates every guarded write before any
-- master/FK row lock. It is reentrant within a transaction and intentionally serializes
-- Customer, Process, Exchange Rate, and relationship writes to eliminate multi-key cycles.
CREATE FUNCTION master_data.coordinate_guarded_master_usage()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(20260730, 1);
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_customer_00_usage_coordination
BEFORE UPDATE ON master_data.customer
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_process_00_usage_coordination
BEFORE UPDATE ON master_data.process_master
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_exchange_rate_00_usage_coordination
BEFORE UPDATE ON master_data.monthly_exchange_rate
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();

CREATE TRIGGER trg_buyer_order_insert_00_usage_coordination
BEFORE INSERT ON sales.buyer_order
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_buyer_order_update_00_usage_coordination
BEFORE UPDATE ON sales.buyer_order
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_stock_position_insert_00_usage_coordination
BEFORE INSERT ON inventory.stock_position
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_stock_position_update_00_usage_coordination
BEFORE UPDATE ON inventory.stock_position
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_delivery_note_insert_00_usage_coordination
BEFORE INSERT ON delivery.delivery_note
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_delivery_note_update_00_usage_coordination
BEFORE UPDATE ON delivery.delivery_note
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_production_order_process_insert_00_usage_coordination
BEFORE INSERT ON production.production_order_process
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_production_order_process_update_00_usage_coordination
BEFORE UPDATE ON production.production_order_process
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();

-- Immediate foreign keys acquire referenced-row KEY SHARE after advisory coordination.
-- AFTER STATEMENT checks only read status and introduce no conflicting lock order.
CREATE FUNCTION master_data.require_active_customer_references()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM new_rows n
        LEFT JOIN master_data.customer c ON c.id = n.customer_id
        WHERE n.customer_id IS NOT NULL
          AND (c.id IS NULL OR c.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: referenced Customer must be ACTIVE'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_buyer_order_insert_active_customer
AFTER INSERT ON sales.buyer_order
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_customer_references();
CREATE TRIGGER trg_buyer_order_update_active_customer
AFTER UPDATE ON sales.buyer_order
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_customer_references();
CREATE TRIGGER trg_stock_position_insert_active_customer
AFTER INSERT ON inventory.stock_position
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_customer_references();
CREATE TRIGGER trg_stock_position_update_active_customer
AFTER UPDATE ON inventory.stock_position
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_customer_references();
CREATE TRIGGER trg_delivery_note_insert_active_customer
AFTER INSERT ON delivery.delivery_note
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_customer_references();
CREATE TRIGGER trg_delivery_note_update_active_customer
AFTER UPDATE ON delivery.delivery_note
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_customer_references();

CREATE FUNCTION master_data.validate_customer_usage_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF (NEW.name IS DISTINCT FROM OLD.name
        OR NEW.address IS DISTINCT FROM OLD.address
        OR NEW.telephone IS DISTINCT FROM OLD.telephone
        OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
        OR NEW.status IS DISTINCT FROM OLD.status)
       AND (
           EXISTS (SELECT 1 FROM sales.buyer_order WHERE customer_id = NEW.id)
           OR EXISTS (SELECT 1 FROM inventory.stock_position WHERE customer_id = NEW.id)
           OR EXISTS (SELECT 1 FROM delivery.delivery_note WHERE customer_id = NEW.id)
       ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: Customer is referenced by business data'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customer_usage_transition
BEFORE UPDATE ON master_data.customer
FOR EACH ROW EXECUTE FUNCTION master_data.validate_customer_usage_transition();

CREATE FUNCTION master_data.require_active_process_references()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM new_rows n
        LEFT JOIN master_data.process_master p ON p.id = n.process_id
        WHERE n.process_id IS NOT NULL
          AND (p.id IS NULL OR p.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: referenced Process must be ACTIVE'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_production_order_process_insert_active_master
AFTER INSERT ON production.production_order_process
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_process_references();
CREATE TRIGGER trg_production_order_process_update_active_master
AFTER UPDATE ON production.production_order_process
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_process_references();

CREATE FUNCTION master_data.validate_process_usage_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF (NEW.name IS DISTINCT FROM OLD.name
        OR NEW.sequence_no IS DISTINCT FROM OLD.sequence_no
        OR NEW.status IS DISTINCT FROM OLD.status)
       AND EXISTS (
           SELECT 1 FROM production.production_order_process WHERE process_id = NEW.id
       ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: Process is referenced by business data'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_process_usage_transition
BEFORE UPDATE ON master_data.process_master
FOR EACH ROW EXECUTE FUNCTION master_data.validate_process_usage_transition();

CREATE FUNCTION master_data.require_active_exchange_rate_inserts()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM new_rows n
        LEFT JOIN master_data.monthly_exchange_rate r ON r.id = n.exchange_rate_id
        WHERE n.exchange_rate_id IS NOT NULL
          AND (r.id IS NULL OR r.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: referenced Exchange Rate must be ACTIVE'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE FUNCTION master_data.require_active_exchange_rate_updates()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM new_rows n
        JOIN old_rows o ON o.id = n.id
        LEFT JOIN master_data.monthly_exchange_rate r ON r.id = n.exchange_rate_id
        WHERE n.exchange_rate_id IS NOT NULL
          AND (
              n.exchange_rate_id IS DISTINCT FROM o.exchange_rate_id
              OR (n.status IN ('POSTED', 'REVERSED') AND o.status = 'DRAFT')
          )
          AND (r.id IS NULL OR r.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: referenced Exchange Rate must be ACTIVE'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_delivery_note_insert_active_exchange_rate
AFTER INSERT ON delivery.delivery_note
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_exchange_rate_inserts();
CREATE TRIGGER trg_delivery_note_update_active_exchange_rate
AFTER UPDATE ON delivery.delivery_note
REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_exchange_rate_updates();

REVOKE ALL ON FUNCTION master_data.coordinate_guarded_master_usage() FROM PUBLIC;
REVOKE ALL ON FUNCTION master_data.require_active_customer_references() FROM PUBLIC;
REVOKE ALL ON FUNCTION master_data.validate_customer_usage_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION master_data.require_active_process_references() FROM PUBLIC;
REVOKE ALL ON FUNCTION master_data.validate_process_usage_transition() FROM PUBLIC;
REVOKE ALL ON FUNCTION master_data.require_active_exchange_rate_inserts() FROM PUBLIC;
REVOKE ALL ON FUNCTION master_data.require_active_exchange_rate_updates() FROM PUBLIC;
