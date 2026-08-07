-- Durable per-Buyer-Order Production Group allocation and full immutable stock-ledger validation.

-- Freeze every writer whose state is inspected or backfilled. An active writer makes the upgrade
-- fail fast instead of allowing a partially observed counter or movement chain.
LOCK TABLE production.production_group, inventory.stock_position, inventory.stock_movement
    IN SHARE ROW EXCLUSIVE MODE NOWAIT;

CREATE TABLE production.production_group_number_counter (
    buyer_order_id uuid NOT NULL,
    last_suffix integer NOT NULL,
    CONSTRAINT pk_production_group_number_counter PRIMARY KEY (buyer_order_id),
    CONSTRAINT fk_production_group_number_counter_order
        FOREIGN KEY (buyer_order_id) REFERENCES sales.buyer_order (id),
    CONSTRAINT ck_production_group_number_counter_suffix
        CHECK (last_suffix BETWEEN 0 AND 999)
);

DO $$
DECLARE
    v_invalid_group text;
BEGIN
    SELECT pg.group_no
    INTO v_invalid_group
    FROM production.production_group pg
    JOIN sales.buyer_order bo ON bo.id = pg.buyer_order_id
    WHERE pg.group_no !~ ('^PG-' || bo.sys_po_no || '-[0-9]{3}$')
    ORDER BY pg.group_no
    LIMIT 1;

    IF v_invalid_group IS NOT NULL THEN
        RAISE EXCEPTION 'PRODUCTION_GROUP_NUMBER_MIGRATION_INVALID: malformed group number %',
            v_invalid_group USING ERRCODE = '23514';
    END IF;

END;
$$;

INSERT INTO production.production_group_number_counter (buyer_order_id, last_suffix)
SELECT buyer_order_id, max(right(group_no, 3)::integer)
FROM production.production_group
GROUP BY buyer_order_id;

CREATE FUNCTION production.next_production_group_no(p_buyer_order_id uuid)
RETURNS varchar(40)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, production, sales
AS $$
DECLARE
    v_sys_po_no varchar(30);
    v_suffix integer;
    v_group_no varchar(40);
BEGIN
    IF p_buyer_order_id IS NULL THEN
        RAISE EXCEPTION 'Buyer Order ID is required'
            USING ERRCODE = '22004';
    END IF;

    SELECT bo.sys_po_no
    INTO v_sys_po_no
    FROM sales.buyer_order bo
    WHERE bo.id = p_buyer_order_id;

    IF NOT FOUND OR v_sys_po_no !~ '^SO-[0-9]{4}-[0-9]{6}$' THEN
        RAISE EXCEPTION 'Buyer Order % has no valid SYS PO number', p_buyer_order_id
            USING ERRCODE = '23514';
    END IF;

    LOOP
        UPDATE production.production_group_number_counter
        SET last_suffix = last_suffix + 1
        WHERE buyer_order_id = p_buyer_order_id
          AND last_suffix < 999
        RETURNING last_suffix INTO v_suffix;

        IF FOUND THEN
            EXIT;
        END IF;

        SELECT last_suffix INTO v_suffix
        FROM production.production_group_number_counter
        WHERE buyer_order_id = p_buyer_order_id;
        IF FOUND THEN
            RAISE EXCEPTION 'Production Group number suffix overflow for Buyer Order %', p_buyer_order_id
                USING ERRCODE = '22003';
        END IF;

        INSERT INTO production.production_group_number_counter (buyer_order_id, last_suffix)
        VALUES (p_buyer_order_id, 1)
        ON CONFLICT (buyer_order_id) DO NOTHING
        RETURNING last_suffix INTO v_suffix;
        IF FOUND THEN
            EXIT;
        END IF;
    END LOOP;

    IF v_suffix IS NULL THEN
        RAISE EXCEPTION 'Production Group number suffix allocation failed for Buyer Order %', p_buyer_order_id
            USING ERRCODE = '23514';
    END IF;

    v_group_no := 'PG-' || v_sys_po_no || '-' || lpad(v_suffix::text, 3, '0');
    IF length(v_group_no) > 40 THEN
        RAISE EXCEPTION 'Production Group number exceeds 40 characters'
            USING ERRCODE = '22001';
    END IF;
    RETURN v_group_no;
END;
$$;

ALTER TABLE inventory.stock_movement
    ADD COLUMN movement_sequence bigint;

-- The ledger is append-only, so the backfill has to suspend that guard for its own statement.
-- Both statements run inside this migration's transaction: a failure rolls the disable back with it.
ALTER TABLE inventory.stock_movement DISABLE TRIGGER trg_stock_movement_append_only;

WITH sequenced AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY stock_position_id
               ORDER BY occurred_at, id
           ) AS movement_sequence
    FROM inventory.stock_movement
)
UPDATE inventory.stock_movement movement
SET movement_sequence = sequenced.movement_sequence
FROM sequenced
WHERE sequenced.id = movement.id;

ALTER TABLE inventory.stock_movement ENABLE TRIGGER trg_stock_movement_append_only;

ALTER TABLE inventory.stock_movement
    ALTER COLUMN movement_sequence SET NOT NULL,
    ADD CONSTRAINT uq_stock_movement_position_sequence
        UNIQUE (stock_position_id, movement_sequence),
    ADD CONSTRAINT ck_stock_movement_sequence CHECK (movement_sequence > 0);

CREATE FUNCTION inventory.assign_stock_movement_sequence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, inventory
AS $$
BEGIN
    PERFORM 1
    FROM inventory.stock_position
    WHERE id = NEW.stock_position_id
    FOR UPDATE;

    SELECT COALESCE(max(movement_sequence), 0) + 1
    INTO NEW.movement_sequence
    FROM inventory.stock_movement
    WHERE stock_position_id = NEW.stock_position_id;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stock_movement_assign_sequence
BEFORE INSERT ON inventory.stock_movement
FOR EACH ROW EXECUTE FUNCTION inventory.assign_stock_movement_sequence();

DO $$
DECLARE
    v_invalid_movement uuid;
BEGIN
    SELECT id
    INTO v_invalid_movement
    FROM (
        SELECT id,
               balance_after,
               sum(quantity_signed) OVER (
                   PARTITION BY stock_position_id
                   ORDER BY movement_sequence
                   ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
               ) AS expected_balance
        FROM inventory.stock_movement
    ) chain
    WHERE balance_after IS DISTINCT FROM expected_balance
    ORDER BY id
    LIMIT 1;

    IF v_invalid_movement IS NOT NULL THEN
        RAISE EXCEPTION 'STOCK_LEDGER_MIGRATION_INVALID: movement % breaks balance chain',
            v_invalid_movement USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM inventory.stock_position position
        LEFT JOIN inventory.stock_movement movement
            ON movement.stock_position_id = position.id
        GROUP BY position.id
        HAVING position.current_qty IS DISTINCT FROM COALESCE(sum(movement.quantity_signed), 0)
    ) THEN
        RAISE EXCEPTION 'STOCK_LEDGER_MIGRATION_INVALID: position aggregate mismatch'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION inventory.validate_stock_ledger(p_stock_position_id uuid)
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, inventory
AS $$
DECLARE
    v_position inventory.stock_position%ROWTYPE;
    v_produced numeric(18,4);
    v_delivered numeric(18,4);
    v_returned numeric(18,4);
    v_disposed numeric(18,4);
    v_current numeric(18,4);
    v_last_balance numeric(18,4);
    v_running_balance numeric(18,4) := 0;
    v_movement record;
BEGIN
    SELECT * INTO v_position
    FROM inventory.stock_position
    WHERE id = p_stock_position_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT COALESCE(sum(quantity_signed) FILTER (WHERE movement_type = 'PRODUCTION'), 0),
           COALESCE(-sum(quantity_signed) FILTER (WHERE movement_type IN ('DELIVERY', 'DELIVERY_REVERSAL')), 0),
           COALESCE(sum(quantity_signed) FILTER (WHERE movement_type = 'RETURN'), 0),
           COALESCE(-sum(quantity_signed) FILTER (WHERE movement_type = 'DISPOSE'), 0),
           COALESCE(sum(quantity_signed), 0)
    INTO v_produced, v_delivered, v_returned, v_disposed, v_current
    FROM inventory.stock_movement
    WHERE stock_position_id = p_stock_position_id;

    FOR v_movement IN
        SELECT id, quantity_signed, balance_after
        FROM inventory.stock_movement
        WHERE stock_position_id = p_stock_position_id
        ORDER BY movement_sequence
    LOOP
        v_running_balance := v_running_balance + v_movement.quantity_signed;
        IF v_movement.balance_after IS DISTINCT FROM v_running_balance THEN
            RAISE EXCEPTION 'Stock Movement % balance_after breaks the stock ledger chain', v_movement.id
                USING ERRCODE = '23514';
        END IF;
        v_last_balance := v_movement.balance_after;
    END LOOP;

    IF v_position.produced_qty <> v_produced
       OR v_position.delivered_qty <> v_delivered
       OR v_position.returned_qty <> v_returned
       OR v_position.disposed_qty <> v_disposed
       OR v_position.current_qty <> v_current
       OR v_position.order_balance_qty <> v_position.order_qty - v_delivered + v_returned
       OR v_last_balance IS DISTINCT FROM v_position.current_qty THEN
        RAISE EXCEPTION 'Stock Position % does not reconcile with its movement ledger', p_stock_position_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

REVOKE ALL ON TABLE production.production_group_number_counter FROM PUBLIC;
REVOKE ALL ON FUNCTION production.next_production_group_no(uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION inventory.assign_stock_movement_sequence() FROM PUBLIC;
REVOKE ALL ON FUNCTION inventory.validate_stock_ledger(uuid) FROM PUBLIC;

DO $$
DECLARE
    v_runtime_role text := current_setting('erp.runtime_role', true);
BEGIN
    IF v_runtime_role IS NULL OR btrim(v_runtime_role) = '' THEN
        RAISE NOTICE 'erp.runtime_role is not configured; V016 runtime grants were not created';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_runtime_role) THEN
        RAISE EXCEPTION 'Configured ERP runtime role % does not exist', v_runtime_role
            USING ERRCODE = '42704';
    END IF;

    EXECUTE format(
        'REVOKE ALL ON production.production_group_number_counter FROM %I',
        v_runtime_role
    );
    EXECUTE format(
        'GRANT EXECUTE ON FUNCTION production.next_production_group_no(uuid) TO %I',
        v_runtime_role
    );
END;
$$;
