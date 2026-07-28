CREATE TABLE inventory.stock_position (
    id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    buyer_order_item_id uuid NOT NULL,
    customer_id uuid NOT NULL,
    currency_code char(3) NOT NULL,
    uom_id uuid NOT NULL,
    order_qty numeric(18,4) NOT NULL,
    produced_qty numeric(18,4) NOT NULL,
    delivered_qty numeric(18,4) NOT NULL DEFAULT 0,
    returned_qty numeric(18,4) NOT NULL DEFAULT 0,
    disposed_qty numeric(18,4) NOT NULL DEFAULT 0,
    current_qty numeric(18,4) NOT NULL,
    order_balance_qty numeric(18,4) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_stock_position PRIMARY KEY (id),
    CONSTRAINT uq_stock_position_production UNIQUE (production_order_id),
    CONSTRAINT uq_stock_position_order_item UNIQUE (buyer_order_item_id),
    CONSTRAINT fk_stock_position_source FOREIGN KEY (production_order_id, buyer_order_item_id)
        REFERENCES production.production_order (id, buyer_order_item_id),
    CONSTRAINT fk_stock_position_customer FOREIGN KEY (customer_id) REFERENCES master_data.customer (id),
    CONSTRAINT fk_stock_position_currency FOREIGN KEY (currency_code) REFERENCES master_data.currency (code),
    CONSTRAINT fk_stock_position_uom FOREIGN KEY (uom_id) REFERENCES master_data.uom (id),
    CONSTRAINT fk_stock_position_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_stock_position_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_stock_position_order_qty CHECK (order_qty > 0),
    CONSTRAINT ck_stock_position_produced CHECK (produced_qty >= 0),
    CONSTRAINT ck_stock_position_delivered CHECK (delivered_qty >= 0),
    CONSTRAINT ck_stock_position_returned CHECK (returned_qty >= 0),
    CONSTRAINT ck_stock_position_disposed CHECK (disposed_qty >= 0),
    CONSTRAINT ck_stock_position_current CHECK (current_qty >= 0),
    CONSTRAINT ck_stock_position_order_balance CHECK (order_balance_qty >= 0),
    CONSTRAINT ck_stock_position_current_formula CHECK (
        current_qty = produced_qty + returned_qty - delivered_qty - disposed_qty
    ),
    CONSTRAINT ck_stock_position_order_balance_formula CHECK (
        order_balance_qty = order_qty - delivered_qty + returned_qty
    ),
    CONSTRAINT ck_stock_position_version CHECK (version >= 0)
);

CREATE INDEX ix_stock_position_customer
    ON inventory.stock_position (customer_id);
CREATE INDEX ix_stock_position_in_stock
    ON inventory.stock_position (current_qty, id)
    WHERE current_qty > 0;
CREATE INDEX ix_stock_position_out_of_stock
    ON inventory.stock_position (id)
    WHERE current_qty = 0;

CREATE TRIGGER trg_stock_position_updated_at
BEFORE UPDATE ON inventory.stock_position
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE FUNCTION inventory.validate_stock_position_source()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(20);
    v_produced numeric(18,4);
    v_customer_id uuid;
    v_currency_code char(3);
    v_uom_id uuid;
    v_order_qty numeric(18,4);
BEGIN
    SELECT po.status,
           po.produced_qty,
           bo.customer_id,
           boi.currency_code,
           boi.uom_id,
           boi.order_qty
    INTO v_status,
         v_produced,
         v_customer_id,
         v_currency_code,
         v_uom_id,
         v_order_qty
    FROM production.production_order po
    JOIN sales.buyer_order_item boi ON boi.id = po.buyer_order_item_id
    JOIN sales.buyer_order bo ON bo.id = boi.buyer_order_id
    WHERE po.id = NEW.production_order_id
      AND po.buyer_order_item_id = NEW.buyer_order_item_id;

    IF NOT FOUND OR v_status <> 'FINISHED' THEN
        RAISE EXCEPTION 'Stock Position requires a FINISHED Production Order'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.produced_qty IS DISTINCT FROM v_produced
       OR NEW.customer_id IS DISTINCT FROM v_customer_id
       OR NEW.currency_code IS DISTINCT FROM v_currency_code
       OR NEW.uom_id IS DISTINCT FROM v_uom_id
       OR NEW.order_qty IS DISTINCT FROM v_order_qty THEN
        RAISE EXCEPTION 'Stock Position source snapshots do not match Production/Buyer Order'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_stock_position_validate_source
BEFORE INSERT OR UPDATE OF production_order_id, buyer_order_item_id, customer_id, currency_code, uom_id, order_qty, produced_qty
ON inventory.stock_position
FOR EACH ROW EXECUTE FUNCTION inventory.validate_stock_position_source();

CREATE TABLE inventory.stock_movement (
    id uuid NOT NULL,
    stock_position_id uuid NOT NULL,
    movement_type varchar(30) NOT NULL,
    quantity_signed numeric(18,4) NOT NULL,
    balance_after numeric(18,4) NOT NULL,
    business_date date NOT NULL,
    source_type varchar(40) NOT NULL,
    source_id uuid NOT NULL,
    source_item_id uuid,
    return_source_delivery_item_id uuid,
    idempotency_key varchar(120) NOT NULL,
    reason text,
    created_by uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_stock_movement PRIMARY KEY (id),
    CONSTRAINT uq_stock_movement_id_position UNIQUE (id, stock_position_id),
    CONSTRAINT uq_stock_movement_position_key UNIQUE (stock_position_id, idempotency_key),
    CONSTRAINT fk_stock_movement_position FOREIGN KEY (stock_position_id) REFERENCES inventory.stock_position (id),
    CONSTRAINT fk_stock_movement_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_stock_movement_type CHECK (
        movement_type IN ('PRODUCTION', 'DELIVERY', 'DELIVERY_REVERSAL', 'RETURN', 'DISPOSE')
    ),
    CONSTRAINT ck_stock_movement_quantity CHECK (quantity_signed <> 0),
    CONSTRAINT ck_stock_movement_sign CHECK (
        (movement_type IN ('PRODUCTION', 'DELIVERY_REVERSAL', 'RETURN') AND quantity_signed > 0)
        OR (movement_type IN ('DELIVERY', 'DISPOSE') AND quantity_signed < 0)
    ),
    CONSTRAINT ck_stock_movement_balance CHECK (balance_after >= 0),
    CONSTRAINT ck_stock_movement_source_type CHECK (btrim(source_type) <> ''),
    CONSTRAINT ck_stock_movement_source_semantics CHECK (
        (movement_type = 'PRODUCTION' AND source_type = 'PRODUCTION' AND source_item_id IS NULL)
        OR (movement_type IN ('DELIVERY', 'DELIVERY_REVERSAL') AND source_type = 'DELIVERY' AND source_item_id IS NOT NULL)
        OR (movement_type = 'RETURN' AND source_type = 'DELIVERY_RETURN')
        OR (movement_type = 'DISPOSE' AND source_type = 'STOCK_ADJUSTMENT' AND source_item_id IS NULL)
    ),
    CONSTRAINT ck_stock_movement_key CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_stock_movement_return_source CHECK (
        (movement_type = 'RETURN' AND return_source_delivery_item_id IS NOT NULL AND reason IS NOT NULL)
        OR (movement_type <> 'RETURN' AND return_source_delivery_item_id IS NULL)
    ),
    CONSTRAINT ck_stock_movement_reason CHECK (
        movement_type NOT IN ('DELIVERY_REVERSAL', 'RETURN', 'DISPOSE') OR btrim(reason) <> ''
    )
);

CREATE INDEX ix_stock_movement_position_time
    ON inventory.stock_movement (stock_position_id, occurred_at, id);
CREATE INDEX ix_stock_movement_source
    ON inventory.stock_movement (source_type, source_id, source_item_id);
CREATE INDEX ix_stock_movement_return_source
    ON inventory.stock_movement (return_source_delivery_item_id)
    WHERE return_source_delivery_item_id IS NOT NULL;

CREATE TRIGGER trg_stock_movement_append_only
BEFORE UPDATE OR DELETE ON inventory.stock_movement
FOR EACH ROW EXECUTE FUNCTION system.prevent_mutation();

CREATE FUNCTION inventory.validate_stock_ledger(p_stock_position_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_position inventory.stock_position%ROWTYPE;
    v_produced numeric(18,4);
    v_delivered numeric(18,4);
    v_returned numeric(18,4);
    v_disposed numeric(18,4);
    v_current numeric(18,4);
    v_last_balance numeric(18,4);
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

    SELECT balance_after INTO v_last_balance
    FROM inventory.stock_movement
    WHERE stock_position_id = p_stock_position_id
    ORDER BY occurred_at DESC, id DESC
    LIMIT 1;

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

CREATE FUNCTION inventory.check_stock_ledger_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_stock_position_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'stock_position' THEN
        v_stock_position_id := COALESCE(NEW.id, OLD.id);
    ELSE
        v_stock_position_id := COALESCE(NEW.stock_position_id, OLD.stock_position_id);
    END IF;

    PERFORM inventory.validate_stock_ledger(v_stock_position_id);
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE CONSTRAINT TRIGGER trg_stock_position_reconcile
AFTER INSERT OR UPDATE ON inventory.stock_position
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_ledger_trigger();

CREATE CONSTRAINT TRIGGER trg_stock_movement_reconcile
AFTER INSERT ON inventory.stock_movement
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION inventory.check_stock_ledger_trigger();
