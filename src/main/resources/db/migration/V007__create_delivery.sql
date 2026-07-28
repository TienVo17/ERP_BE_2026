CREATE TABLE delivery.delivery_note (
    id uuid NOT NULL,
    delivery_no varchar(30),
    customer_id uuid NOT NULL,
    customer_name_snapshot varchar(200) NOT NULL,
    customer_address_snapshot text,
    delivery_date date NOT NULL,
    currency_code char(3) NOT NULL,
    exchange_rate_id uuid,
    vnd_usd_rate_snapshot numeric(18,6),
    won_usd_rate_snapshot numeric(18,6),
    vat_percent numeric(7,4) NOT NULL DEFAULT 0,
    remark text,
    total_qty numeric(18,4) NOT NULL,
    total_amount numeric(18,2) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'DRAFT',
    replaces_delivery_id uuid,
    posted_at timestamptz,
    posted_by uuid,
    reversed_at timestamptz,
    reversed_by uuid,
    reversal_reason text,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_delivery_note PRIMARY KEY (id),
    CONSTRAINT uq_delivery_note_no UNIQUE (delivery_no),
    CONSTRAINT uq_delivery_note_replacement UNIQUE (replaces_delivery_id),
    CONSTRAINT fk_delivery_note_customer FOREIGN KEY (customer_id) REFERENCES master_data.customer (id),
    CONSTRAINT fk_delivery_note_currency FOREIGN KEY (currency_code) REFERENCES master_data.currency (code),
    CONSTRAINT fk_delivery_note_rate FOREIGN KEY (exchange_rate_id) REFERENCES master_data.monthly_exchange_rate (id),
    CONSTRAINT fk_delivery_note_replaces FOREIGN KEY (replaces_delivery_id) REFERENCES delivery.delivery_note (id),
    CONSTRAINT fk_delivery_note_posted_by FOREIGN KEY (posted_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_delivery_note_reversed_by FOREIGN KEY (reversed_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_delivery_note_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_delivery_note_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_delivery_note_no CHECK (delivery_no IS NULL OR delivery_no ~ '^DN-[0-9]{4}-[0-9]{6}$'),
    CONSTRAINT ck_delivery_note_customer_name CHECK (btrim(customer_name_snapshot) <> ''),
    CONSTRAINT ck_delivery_note_rates CHECK (
        (vnd_usd_rate_snapshot IS NULL OR vnd_usd_rate_snapshot > 0)
        AND (won_usd_rate_snapshot IS NULL OR won_usd_rate_snapshot > 0)
    ),
    CONSTRAINT ck_delivery_note_vat CHECK (vat_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_delivery_note_totals CHECK (total_qty >= 0 AND total_amount >= 0),
    CONSTRAINT ck_delivery_note_status CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),
    CONSTRAINT ck_delivery_note_post_state CHECK (
        (status = 'DRAFT'
            AND delivery_no IS NULL
            AND posted_at IS NULL
            AND posted_by IS NULL
            AND reversed_at IS NULL
            AND reversed_by IS NULL
            AND reversal_reason IS NULL)
        OR (status = 'POSTED'
            AND delivery_no IS NOT NULL
            AND exchange_rate_id IS NOT NULL
            AND vnd_usd_rate_snapshot IS NOT NULL
            AND won_usd_rate_snapshot IS NOT NULL
            AND posted_at IS NOT NULL
            AND posted_by IS NOT NULL
            AND reversed_at IS NULL
            AND reversed_by IS NULL
            AND reversal_reason IS NULL)
        OR (status = 'REVERSED'
            AND delivery_no IS NOT NULL
            AND exchange_rate_id IS NOT NULL
            AND vnd_usd_rate_snapshot IS NOT NULL
            AND won_usd_rate_snapshot IS NOT NULL
            AND posted_at IS NOT NULL
            AND posted_by IS NOT NULL
            AND reversed_at IS NOT NULL
            AND reversed_by IS NOT NULL
            AND btrim(reversal_reason) <> '')
    ),
    CONSTRAINT ck_delivery_note_replacement_self CHECK (replaces_delivery_id IS NULL OR replaces_delivery_id <> id),
    CONSTRAINT ck_delivery_note_version CHECK (version >= 0)
);

CREATE INDEX ix_delivery_note_status_date
    ON delivery.delivery_note (status, delivery_date);
CREATE INDEX ix_delivery_note_customer_date
    ON delivery.delivery_note (customer_id, delivery_date);

CREATE TRIGGER trg_delivery_note_updated_at
BEFORE UPDATE ON delivery.delivery_note
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE FUNCTION delivery.protect_delivery_note_state()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'REVERSED' THEN
        RAISE EXCEPTION 'Reversed Delivery Note is immutable'
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status = 'POSTED' THEN
        IF NEW.status <> 'REVERSED' THEN
            RAISE EXCEPTION 'Posted Delivery Note can only transition to REVERSED'
                USING ERRCODE = '23514';
        END IF;

        IF (to_jsonb(NEW) - ARRAY['status', 'reversed_at', 'reversed_by', 'reversal_reason', 'updated_at', 'updated_by', 'version'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['status', 'reversed_at', 'reversed_by', 'reversal_reason', 'updated_at', 'updated_by', 'version']) THEN
            RAISE EXCEPTION 'Posted Delivery Note business fields are immutable'
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_delivery_note_protect_state
BEFORE UPDATE ON delivery.delivery_note
FOR EACH ROW EXECUTE FUNCTION delivery.protect_delivery_note_state();

CREATE TABLE delivery.delivery_note_item (
    id uuid NOT NULL,
    delivery_note_id uuid NOT NULL,
    line_no integer NOT NULL,
    stock_position_id uuid NOT NULL,
    buyer_order_id uuid NOT NULL,
    buyer_order_item_id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    sys_po_no_snapshot varchar(30) NOT NULL,
    buyer_po_snapshot varchar(120) NOT NULL,
    pic_name_snapshot varchar(200) NOT NULL,
    po_date_snapshot date NOT NULL,
    promised_delivery_date_snapshot date NOT NULL,
    product_kind_snapshot varchar(20) NOT NULL,
    style_no_snapshot varchar(120) NOT NULL,
    name_snapshot varchar(200) NOT NULL,
    size_snapshot varchar(120),
    color_snapshot varchar(120),
    uom_code_snapshot varchar(30) NOT NULL,
    currency_code char(3) NOT NULL,
    order_qty_snapshot numeric(18,4) NOT NULL,
    produced_qty_snapshot numeric(18,4) NOT NULL,
    delivery_qty numeric(18,4) NOT NULL,
    unit_price numeric(18,6) NOT NULL,
    amount numeric(18,2) NOT NULL,
    delivery_movement_id uuid,
    reversal_movement_id uuid,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_delivery_note_item PRIMARY KEY (id),
    CONSTRAINT uq_delivery_note_item_line UNIQUE (delivery_note_id, line_no),
    CONSTRAINT uq_delivery_note_item_position UNIQUE (delivery_note_id, stock_position_id),
    CONSTRAINT uq_delivery_note_item_delivery_movement UNIQUE (delivery_movement_id),
    CONSTRAINT uq_delivery_note_item_reversal_movement UNIQUE (reversal_movement_id),
    CONSTRAINT fk_delivery_note_item_note FOREIGN KEY (delivery_note_id) REFERENCES delivery.delivery_note (id),
    CONSTRAINT fk_delivery_note_item_position FOREIGN KEY (stock_position_id) REFERENCES inventory.stock_position (id),
    CONSTRAINT fk_delivery_note_item_order FOREIGN KEY (buyer_order_id) REFERENCES sales.buyer_order (id),
    CONSTRAINT fk_delivery_note_item_order_item FOREIGN KEY (buyer_order_item_id, buyer_order_id)
        REFERENCES sales.buyer_order_item (id, buyer_order_id),
    CONSTRAINT fk_delivery_note_item_production FOREIGN KEY (production_order_id, buyer_order_item_id)
        REFERENCES production.production_order (id, buyer_order_item_id),
    CONSTRAINT fk_delivery_note_item_delivery_movement FOREIGN KEY (delivery_movement_id, stock_position_id)
        REFERENCES inventory.stock_movement (id, stock_position_id),
    CONSTRAINT fk_delivery_note_item_reversal_movement FOREIGN KEY (reversal_movement_id, stock_position_id)
        REFERENCES inventory.stock_movement (id, stock_position_id),
    CONSTRAINT ck_delivery_note_item_line CHECK (line_no > 0),
    CONSTRAINT ck_delivery_note_item_kind CHECK (product_kind_snapshot IN ('PRINT', 'WOVEN')),
    CONSTRAINT ck_delivery_note_item_snapshots CHECK (
        btrim(sys_po_no_snapshot) <> ''
        AND btrim(buyer_po_snapshot) <> ''
        AND btrim(pic_name_snapshot) <> ''
        AND btrim(style_no_snapshot) <> ''
        AND btrim(name_snapshot) <> ''
        AND btrim(uom_code_snapshot) <> ''
    ),
    CONSTRAINT ck_delivery_note_item_order_qty CHECK (order_qty_snapshot > 0),
    CONSTRAINT ck_delivery_note_item_produced_qty CHECK (produced_qty_snapshot >= 0),
    CONSTRAINT ck_delivery_note_item_qty CHECK (delivery_qty > 0),
    CONSTRAINT ck_delivery_note_item_price CHECK (unit_price >= 0),
    CONSTRAINT ck_delivery_note_item_amount CHECK (amount = round(delivery_qty * unit_price, 2))
);

CREATE INDEX ix_delivery_note_item_position
    ON delivery.delivery_note_item (stock_position_id);
CREATE INDEX ix_delivery_note_item_production
    ON delivery.delivery_note_item (production_order_id);

CREATE TRIGGER trg_delivery_note_item_updated_at
BEFORE UPDATE ON delivery.delivery_note_item
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

ALTER TABLE inventory.stock_movement
    ADD CONSTRAINT fk_stock_movement_return_source
    FOREIGN KEY (return_source_delivery_item_id)
    REFERENCES delivery.delivery_note_item (id),
    ADD CONSTRAINT fk_stock_movement_delivery_source_item
    FOREIGN KEY (source_item_id)
    REFERENCES delivery.delivery_note_item (id);

CREATE UNIQUE INDEX uq_stock_movement_delivery_source_line
    ON inventory.stock_movement (source_id, source_item_id, movement_type)
    WHERE source_type = 'DELIVERY'
      AND movement_type IN ('DELIVERY', 'DELIVERY_REVERSAL');

CREATE TABLE delivery.delivery_event (
    id uuid NOT NULL,
    delivery_note_id uuid NOT NULL,
    event_type varchar(30) NOT NULL,
    actor_user_id uuid NOT NULL,
    reason text,
    payload jsonb,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_delivery_event PRIMARY KEY (id),
    CONSTRAINT fk_delivery_event_note FOREIGN KEY (delivery_note_id) REFERENCES delivery.delivery_note (id),
    CONSTRAINT fk_delivery_event_actor FOREIGN KEY (actor_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_delivery_event_type CHECK (
        event_type IN ('CREATED', 'UPDATED_DRAFT', 'POSTED', 'REVERSED', 'REPLACED', 'PRINTED')
    )
);

CREATE INDEX ix_delivery_event_note_time
    ON delivery.delivery_event (delivery_note_id, occurred_at, id);

CREATE TRIGGER trg_delivery_event_append_only
BEFORE UPDATE OR DELETE ON delivery.delivery_event
FOR EACH ROW EXECUTE FUNCTION system.prevent_mutation();

CREATE FUNCTION delivery.protect_delivery_item()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(20);
BEGIN
    SELECT status INTO v_status
    FROM delivery.delivery_note
    WHERE id = COALESCE(NEW.delivery_note_id, OLD.delivery_note_id);

    IF TG_OP = 'UPDATE' AND v_status = 'POSTED' THEN
        IF (to_jsonb(NEW) - ARRAY['reversal_movement_id', 'updated_at'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['reversal_movement_id', 'updated_at']) THEN
            RAISE EXCEPTION 'Posted Delivery Item only permits linking its reversal movement'
                USING ERRCODE = '55000';
        END IF;
        RETURN NEW;
    END IF;

    IF v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Delivery Items are mutable only while the Delivery Note is DRAFT'
            USING ERRCODE = '55000';
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_delivery_note_item_protect
BEFORE INSERT OR UPDATE OR DELETE ON delivery.delivery_note_item
FOR EACH ROW EXECUTE FUNCTION delivery.protect_delivery_item();

CREATE FUNCTION delivery.validate_return_movement(p_movement_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_movement inventory.stock_movement%ROWTYPE;
    v_item delivery.delivery_note_item%ROWTYPE;
    v_note_status varchar(20);
    v_returned numeric(18,4);
BEGIN
    SELECT * INTO v_movement
    FROM inventory.stock_movement
    WHERE id = p_movement_id;

    IF NOT FOUND OR v_movement.movement_type <> 'RETURN' THEN
        RETURN;
    END IF;

    SELECT dni.*
    INTO v_item
    FROM delivery.delivery_note_item dni
    WHERE dni.id = v_movement.return_source_delivery_item_id;

    IF FOUND THEN
        SELECT status
        INTO v_note_status
        FROM delivery.delivery_note
        WHERE id = v_item.delivery_note_id;
    END IF;

    IF NOT FOUND
       OR v_note_status <> 'POSTED'
       OR v_item.stock_position_id <> v_movement.stock_position_id THEN
        RAISE EXCEPTION 'RETURN requires a POSTED Delivery Item from the same Stock Position'
            USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE(sum(quantity_signed), 0)
    INTO v_returned
    FROM inventory.stock_movement
    WHERE movement_type = 'RETURN'
      AND return_source_delivery_item_id = v_item.id;

    IF v_returned > v_item.delivery_qty THEN
        RAISE EXCEPTION 'Returns exceed the delivered quantity for Delivery Item %', v_item.id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION delivery.validate_delivery_integrity(p_delivery_note_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_note delivery.delivery_note%ROWTYPE;
    v_item_count integer;
    v_total_qty numeric(18,4);
    v_total_amount numeric(18,2);
    v_invalid_count integer;
    v_return_count integer;
    v_rate_month date;
BEGIN
    SELECT * INTO v_note
    FROM delivery.delivery_note
    WHERE id = p_delivery_note_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*), COALESCE(sum(delivery_qty), 0), COALESCE(sum(amount), 0)
    INTO v_item_count, v_total_qty, v_total_amount
    FROM delivery.delivery_note_item
    WHERE delivery_note_id = p_delivery_note_id;

    IF v_item_count = 0 THEN
        RAISE EXCEPTION 'Delivery Note % requires at least one item', p_delivery_note_id
            USING ERRCODE = '23514';
    END IF;

    IF v_note.total_qty <> v_total_qty OR v_note.total_amount <> v_total_amount THEN
        RAISE EXCEPTION 'Delivery Note totals do not match its items'
            USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO v_invalid_count
    FROM delivery.delivery_note_item dni
    JOIN inventory.stock_position sp ON sp.id = dni.stock_position_id
    WHERE dni.delivery_note_id = p_delivery_note_id
      AND (
          sp.customer_id <> v_note.customer_id
          OR sp.currency_code <> v_note.currency_code
          OR dni.currency_code <> v_note.currency_code
          OR sp.production_order_id <> dni.production_order_id
          OR sp.buyer_order_item_id <> dni.buyer_order_item_id
          OR sp.order_qty <> dni.order_qty_snapshot
          OR sp.produced_qty <> dni.produced_qty_snapshot
      );

    IF v_invalid_count > 0 THEN
        RAISE EXCEPTION 'Delivery Items do not match authoritative Stock Positions'
            USING ERRCODE = '23514';
    END IF;

    IF v_note.status IN ('POSTED', 'REVERSED') THEN
        SELECT effective_month INTO v_rate_month
        FROM master_data.monthly_exchange_rate
        WHERE id = v_note.exchange_rate_id;

        IF v_rate_month IS DISTINCT FROM date_trunc('month', v_note.delivery_date)::date THEN
            RAISE EXCEPTION 'Delivery exchange-rate month does not match delivery date'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*) INTO v_invalid_count
        FROM delivery.delivery_note_item dni
        WHERE dni.delivery_note_id = p_delivery_note_id
          AND (
              SELECT count(*)
              FROM inventory.stock_movement dm
              WHERE dm.stock_position_id = dni.stock_position_id
                AND dm.movement_type = 'DELIVERY'
                AND dm.source_type = 'DELIVERY'
                AND dm.source_id = dni.delivery_note_id
                AND dm.source_item_id = dni.id
                AND dm.quantity_signed = -dni.delivery_qty
                AND dm.id = dni.delivery_movement_id
          ) <> 1;

        IF v_invalid_count > 0 THEN
            RAISE EXCEPTION 'POSTED Delivery Items require matching DELIVERY movements'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF v_note.status = 'REVERSED' THEN
        SELECT count(*) INTO v_return_count
        FROM delivery.delivery_note_item dni
        JOIN inventory.stock_movement sm ON sm.return_source_delivery_item_id = dni.id
        WHERE dni.delivery_note_id = p_delivery_note_id
          AND sm.movement_type = 'RETURN';

        IF v_return_count > 0 THEN
            RAISE EXCEPTION 'Delivery Note with returned items cannot be reversed in phase one'
                USING ERRCODE = '23514';
        END IF;

        SELECT count(*) INTO v_invalid_count
        FROM delivery.delivery_note_item dni
        WHERE dni.delivery_note_id = p_delivery_note_id
          AND (
              SELECT count(*)
              FROM inventory.stock_movement rm
              WHERE rm.stock_position_id = dni.stock_position_id
                AND rm.movement_type = 'DELIVERY_REVERSAL'
                AND rm.source_type = 'DELIVERY'
                AND rm.source_id = dni.delivery_note_id
                AND rm.source_item_id = dni.id
                AND rm.quantity_signed = dni.delivery_qty
                AND rm.id = dni.reversal_movement_id
          ) <> 1;

        IF v_invalid_count > 0 THEN
            RAISE EXCEPTION 'REVERSED Delivery Items require matching DELIVERY_REVERSAL movements'
                USING ERRCODE = '23514';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION delivery.check_delivery_note_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM delivery.validate_delivery_integrity(COALESCE(NEW.id, OLD.id));
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE FUNCTION delivery.check_delivery_item_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM delivery.validate_delivery_integrity(COALESCE(NEW.delivery_note_id, OLD.delivery_note_id));
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE FUNCTION delivery.check_movement_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_delivery_note_id uuid;
BEGIN
    IF NEW.movement_type = 'RETURN' THEN
        PERFORM delivery.validate_return_movement(NEW.id);
        SELECT delivery_note_id INTO v_delivery_note_id
        FROM delivery.delivery_note_item
        WHERE id = NEW.return_source_delivery_item_id;
    ELSIF NEW.movement_type IN ('DELIVERY', 'DELIVERY_REVERSAL') AND NEW.source_type = 'DELIVERY' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM delivery.delivery_note_item dni
            WHERE dni.id = NEW.source_item_id
              AND dni.delivery_note_id = NEW.source_id
              AND dni.stock_position_id = NEW.stock_position_id
        ) THEN
            RAISE EXCEPTION 'Delivery movement source document, item, and Stock Position do not match'
                USING ERRCODE = '23514';
        END IF;
        v_delivery_note_id := NEW.source_id;
    END IF;

    IF v_delivery_note_id IS NOT NULL THEN
        PERFORM delivery.validate_delivery_integrity(v_delivery_note_id);
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_delivery_note_integrity
AFTER INSERT OR UPDATE ON delivery.delivery_note
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION delivery.check_delivery_note_trigger();

CREATE CONSTRAINT TRIGGER trg_delivery_item_integrity
AFTER INSERT OR UPDATE OR DELETE ON delivery.delivery_note_item
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION delivery.check_delivery_item_trigger();

CREATE CONSTRAINT TRIGGER trg_delivery_movement_integrity
AFTER INSERT ON inventory.stock_movement
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION delivery.check_movement_trigger();

CREATE FUNCTION delivery.prevent_used_exchange_rate_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM delivery.delivery_note
        WHERE exchange_rate_id = OLD.id
          AND status IN ('POSTED', 'REVERSED')
    ) THEN
        RAISE EXCEPTION 'Exchange Rate used by a posted Delivery cannot be changed or deleted'
            USING ERRCODE = '55000';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_exchange_rate_protect_used
BEFORE UPDATE OR DELETE ON master_data.monthly_exchange_rate
FOR EACH ROW EXECUTE FUNCTION delivery.prevent_used_exchange_rate_change();

CREATE FUNCTION sales.guard_buyer_order_reopen()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CONFIRMED' AND NEW.status = 'STANDBY' THEN
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
END;
$$;

CREATE TRIGGER trg_buyer_order_guard_reopen
BEFORE UPDATE OF status ON sales.buyer_order
FOR EACH ROW EXECUTE FUNCTION sales.guard_buyer_order_reopen();

CREATE FUNCTION sales.validate_reopened_buyer_order()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'STANDBY' AND OLD.status = 'CONFIRMED' AND EXISTS (
        SELECT 1
        FROM production.production_order
        WHERE buyer_order_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Reopening Buyer Order requires removing its OPEN Production Orders in the same transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_buyer_order_reopen_atomic
AFTER UPDATE OF status ON sales.buyer_order
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION sales.validate_reopened_buyer_order();
