CREATE TABLE sales.buyer_order (
    id uuid NOT NULL,
    sys_po_no varchar(30) NOT NULL,
    order_type varchar(80) NOT NULL,
    customer_id uuid NOT NULL,
    customer_name_snapshot varchar(200) NOT NULL,
    customer_short_name_snapshot varchar(50) NOT NULL,
    customer_contact_id uuid,
    pic_source varchar(20) NOT NULL,
    pic_name_snapshot varchar(200) NOT NULL,
    buyer_po varchar(120) NOT NULL,
    po_date date NOT NULL,
    delivery_date date NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'STANDBY',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_buyer_order PRIMARY KEY (id),
    CONSTRAINT uq_buyer_order_sys_po UNIQUE (sys_po_no),
    CONSTRAINT fk_buyer_order_customer FOREIGN KEY (customer_id) REFERENCES master_data.customer (id),
    CONSTRAINT fk_buyer_order_contact_customer FOREIGN KEY (customer_contact_id, customer_id)
        REFERENCES master_data.customer_contact (id, customer_id),
    CONSTRAINT fk_buyer_order_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_buyer_order_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_buyer_order_number CHECK (sys_po_no ~ '^SO-[0-9]{4}-[0-9]{6}$'),
    CONSTRAINT ck_buyer_order_type CHECK (btrim(order_type) <> ''),
    CONSTRAINT ck_buyer_order_customer_name CHECK (btrim(customer_name_snapshot) <> ''),
    CONSTRAINT ck_buyer_order_customer_short_name CHECK (btrim(customer_short_name_snapshot) <> ''),
    CONSTRAINT ck_buyer_order_pic_source CHECK (pic_source IN ('MASTER', 'CUSTOM')),
    CONSTRAINT ck_buyer_order_pic_reference CHECK (
        (pic_source = 'MASTER' AND customer_contact_id IS NOT NULL)
        OR (pic_source = 'CUSTOM' AND customer_contact_id IS NULL)
    ),
    CONSTRAINT ck_buyer_order_pic_name CHECK (btrim(pic_name_snapshot) <> ''),
    CONSTRAINT ck_buyer_order_buyer_po CHECK (btrim(buyer_po) <> ''),
    CONSTRAINT ck_buyer_order_dates CHECK (delivery_date >= po_date),
    CONSTRAINT ck_buyer_order_status CHECK (status IN ('STANDBY', 'CONFIRMED')),
    CONSTRAINT ck_buyer_order_version CHECK (version >= 0)
);

CREATE INDEX ix_buyer_order_status_po_date
    ON sales.buyer_order (status, po_date);
CREATE INDEX ix_buyer_order_customer_po_date
    ON sales.buyer_order (customer_id, po_date);
CREATE INDEX ix_buyer_order_buyer_po
    ON sales.buyer_order (buyer_po);

CREATE TRIGGER trg_buyer_order_updated_at
BEFORE UPDATE ON sales.buyer_order
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE FUNCTION sales.protect_confirmed_buyer_order()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CONFIRMED' AND NEW.status = 'CONFIRMED'
       AND (to_jsonb(NEW) - ARRAY['updated_at', 'updated_by', 'version'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['updated_at', 'updated_by', 'version']) THEN
        RAISE EXCEPTION 'Confirmed Buyer Order is immutable; reopen it first'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.sys_po_no IS DISTINCT FROM OLD.sys_po_no THEN
        RAISE EXCEPTION 'SYS PO number is immutable'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_buyer_order_protect_confirmed
BEFORE UPDATE ON sales.buyer_order
FOR EACH ROW EXECUTE FUNCTION sales.protect_confirmed_buyer_order();

CREATE TABLE sales.buyer_order_item (
    id uuid NOT NULL,
    buyer_order_id uuid NOT NULL,
    line_no integer NOT NULL,
    is_custom boolean NOT NULL DEFAULT false,
    finished_good_id uuid,
    product_kind_snapshot varchar(20) NOT NULL,
    style_no_snapshot varchar(120) NOT NULL,
    name_snapshot varchar(200) NOT NULL,
    size_snapshot varchar(120),
    color_snapshot varchar(120),
    uom_id uuid NOT NULL,
    uom_code_snapshot varchar(30) NOT NULL,
    order_qty numeric(18,4) NOT NULL,
    use_stock_qty numeric(18,4) NOT NULL DEFAULT 0,
    production_qty numeric(18,4) NOT NULL,
    unit_price numeric(18,6) NOT NULL,
    currency_code char(3) NOT NULL,
    amount numeric(18,2) NOT NULL,
    remark text,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_buyer_order_item PRIMARY KEY (id),
    CONSTRAINT uq_buyer_order_item_line UNIQUE (buyer_order_id, line_no),
    CONSTRAINT uq_buyer_order_item_id_order UNIQUE (id, buyer_order_id),
    CONSTRAINT fk_buyer_order_item_order FOREIGN KEY (buyer_order_id) REFERENCES sales.buyer_order (id),
    CONSTRAINT fk_buyer_order_item_finished_good FOREIGN KEY (finished_good_id) REFERENCES master_data.finished_good (id),
    CONSTRAINT fk_buyer_order_item_uom FOREIGN KEY (uom_id) REFERENCES master_data.uom (id),
    CONSTRAINT fk_buyer_order_item_currency FOREIGN KEY (currency_code) REFERENCES master_data.currency (code),
    CONSTRAINT fk_buyer_order_item_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_buyer_order_item_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_buyer_order_item_line CHECK (line_no > 0),
    CONSTRAINT ck_buyer_order_item_source CHECK (
        (is_custom AND finished_good_id IS NULL)
        OR (NOT is_custom AND finished_good_id IS NOT NULL)
    ),
    CONSTRAINT ck_buyer_order_item_kind CHECK (product_kind_snapshot IN ('PRINT', 'WOVEN')),
    CONSTRAINT ck_buyer_order_item_style CHECK (btrim(style_no_snapshot) <> ''),
    CONSTRAINT ck_buyer_order_item_name CHECK (btrim(name_snapshot) <> ''),
    CONSTRAINT ck_buyer_order_item_uom_code CHECK (btrim(uom_code_snapshot) <> ''),
    CONSTRAINT ck_buyer_order_item_order_qty CHECK (order_qty > 0),
    CONSTRAINT ck_buyer_order_item_use_stock_phase_one CHECK (use_stock_qty = 0),
    CONSTRAINT ck_buyer_order_item_production_qty CHECK (production_qty = order_qty),
    CONSTRAINT ck_buyer_order_item_price CHECK (unit_price >= 0),
    CONSTRAINT ck_buyer_order_item_amount CHECK (amount = round(order_qty * unit_price, 2))
);

CREATE INDEX ix_buyer_order_item_finished_good
    ON sales.buyer_order_item (finished_good_id);
CREATE INDEX ix_buyer_order_item_uom
    ON sales.buyer_order_item (uom_id);

CREATE TRIGGER trg_buyer_order_item_updated_at
BEFORE UPDATE ON sales.buyer_order_item
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE FUNCTION sales.protect_confirmed_buyer_order_item()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(20);
BEGIN
    SELECT status INTO v_status
    FROM sales.buyer_order
    WHERE id = COALESCE(NEW.buyer_order_id, OLD.buyer_order_id);

    IF v_status = 'CONFIRMED' THEN
        RAISE EXCEPTION 'Items of a confirmed Buyer Order are immutable'
            USING ERRCODE = '55000';
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER trg_buyer_order_item_protect_confirmed
BEFORE INSERT OR UPDATE OR DELETE ON sales.buyer_order_item
FOR EACH ROW EXECUTE FUNCTION sales.protect_confirmed_buyer_order_item();
