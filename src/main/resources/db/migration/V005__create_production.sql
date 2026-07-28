CREATE TABLE production.production_group (
    id uuid NOT NULL,
    buyer_order_id uuid NOT NULL,
    group_no varchar(40) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_production_group PRIMARY KEY (id),
    CONSTRAINT uq_production_group_id_order UNIQUE (id, buyer_order_id),
    CONSTRAINT uq_production_group_no UNIQUE (group_no),
    CONSTRAINT fk_production_group_order FOREIGN KEY (buyer_order_id) REFERENCES sales.buyer_order (id),
    CONSTRAINT fk_production_group_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_production_group_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_group_no CHECK (btrim(group_no) <> ''),
    CONSTRAINT ck_production_group_version CHECK (version >= 0)
);

CREATE TRIGGER trg_production_group_updated_at
BEFORE UPDATE ON production.production_group
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE production.production_order (
    id uuid NOT NULL,
    production_no varchar(30) NOT NULL,
    buyer_order_item_id uuid NOT NULL,
    buyer_order_id uuid NOT NULL,
    production_group_id uuid,
    product_kind_snapshot varchar(20) NOT NULL,
    product_no varchar(120) NOT NULL,
    qr_value varchar(255) NOT NULL,
    planned_qty numeric(18,4) NOT NULL,
    produced_qty numeric(18,4),
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    finished_at timestamptz,
    finished_by uuid,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_production_order PRIMARY KEY (id),
    CONSTRAINT uq_production_order_no UNIQUE (production_no),
    CONSTRAINT uq_production_order_qr UNIQUE (qr_value),
    CONSTRAINT uq_production_order_source_item UNIQUE (buyer_order_item_id),
    CONSTRAINT uq_production_order_id_source UNIQUE (id, buyer_order_item_id),
    CONSTRAINT fk_production_order_source FOREIGN KEY (buyer_order_item_id, buyer_order_id)
        REFERENCES sales.buyer_order_item (id, buyer_order_id),
    CONSTRAINT fk_production_order_group_scope FOREIGN KEY (production_group_id, buyer_order_id)
        REFERENCES production.production_group (id, buyer_order_id),
    CONSTRAINT fk_production_order_finished_by FOREIGN KEY (finished_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_production_order_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_production_order_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_order_no CHECK (production_no ~ '^PR-[0-9]{4}-[0-9]{6}$'),
    CONSTRAINT ck_production_order_kind CHECK (product_kind_snapshot IN ('PRINT', 'WOVEN')),
    CONSTRAINT ck_production_order_product_no CHECK (btrim(product_no) <> ''),
    CONSTRAINT ck_production_order_qr CHECK (btrim(qr_value) <> ''),
    CONSTRAINT ck_production_order_planned_qty CHECK (planned_qty > 0),
    CONSTRAINT ck_production_order_produced_qty CHECK (produced_qty IS NULL OR produced_qty >= 0),
    CONSTRAINT ck_production_order_status CHECK (status IN ('OPEN', 'FINISHED')),
    CONSTRAINT ck_production_order_finish_state CHECK (
        (status = 'OPEN' AND produced_qty IS NULL AND finished_at IS NULL AND finished_by IS NULL)
        OR (status = 'FINISHED' AND produced_qty IS NOT NULL AND finished_at IS NOT NULL AND finished_by IS NOT NULL)
    ),
    CONSTRAINT ck_production_order_version CHECK (version >= 0)
);

CREATE INDEX ix_production_order_status_created
    ON production.production_order (status, created_at);
CREATE INDEX ix_production_order_group
    ON production.production_order (production_group_id)
    WHERE production_group_id IS NOT NULL;
CREATE INDEX ix_production_order_buyer_order
    ON production.production_order (buyer_order_id);

CREATE TRIGGER trg_production_order_updated_at
BEFORE UPDATE ON production.production_order
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE FUNCTION production.protect_finished_order()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'FINISHED'
       AND (to_jsonb(NEW) - ARRAY['updated_at', 'updated_by', 'version'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['updated_at', 'updated_by', 'version']) THEN
        RAISE EXCEPTION 'Finished Production Order is immutable'
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

    IF OLD.status = 'FINISHED' AND NEW.status <> 'FINISHED' THEN
        RAISE EXCEPTION 'Finished Production Order cannot be reopened'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_production_order_protect_finished
BEFORE UPDATE ON production.production_order
FOR EACH ROW EXECUTE FUNCTION production.protect_finished_order();

CREATE TABLE production.production_print_config (
    production_order_id uuid NOT NULL,
    material_source varchar(20),
    order_kind varchar(40),
    remark text,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_production_print_config PRIMARY KEY (production_order_id),
    CONSTRAINT fk_production_print_order FOREIGN KEY (production_order_id) REFERENCES production.production_order (id),
    CONSTRAINT fk_production_print_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_print_material_source CHECK (material_source IS NULL OR material_source IN ('STOCK', 'PURCHASE')),
    CONSTRAINT ck_production_print_order_kind CHECK (
        order_kind IS NULL OR order_kind IN ('LAYOUT', 'SAMPLE', 'NEW_ORDER', 'REPEAT_ORDER', 'SECOND_PRODUCTION')
    )
);

CREATE TRIGGER trg_production_print_updated_at
BEFORE UPDATE ON production.production_print_config
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE production.production_woven_config (
    production_order_id uuid NOT NULL,
    bim varchar(40),
    ten_dia varchar(120),
    mat_do varchar(120),
    pick varchar(120),
    x_ngang_mm numeric(18,4),
    y_doc_mm numeric(18,4),
    hai_da_mm numeric(18,4),
    logo_x_mm numeric(18,4),
    logo_y_mm numeric(18,4),
    ho_percent numeric(7,4),
    remark text,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_production_woven_config PRIMARY KEY (production_order_id),
    CONSTRAINT fk_production_woven_order FOREIGN KEY (production_order_id) REFERENCES production.production_order (id),
    CONSTRAINT fk_production_woven_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_woven_bim CHECK (bim IS NULL OR bim IN ('WHITE', 'BLACK', '100D', '75D', '50D')),
    CONSTRAINT ck_production_woven_x CHECK (x_ngang_mm IS NULL OR x_ngang_mm > 0),
    CONSTRAINT ck_production_woven_y CHECK (y_doc_mm IS NULL OR y_doc_mm > 0),
    CONSTRAINT ck_production_woven_hai_da CHECK (hai_da_mm IS NULL OR hai_da_mm >= 0),
    CONSTRAINT ck_production_woven_logo_x CHECK (logo_x_mm IS NULL OR logo_x_mm >= 0),
    CONSTRAINT ck_production_woven_logo_y CHECK (logo_y_mm IS NULL OR logo_y_mm >= 0),
    CONSTRAINT ck_production_woven_ho CHECK (ho_percent IS NULL OR ho_percent BETWEEN 0 AND 100)
);

CREATE TRIGGER trg_production_woven_updated_at
BEFORE UPDATE ON production.production_woven_config
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE production.production_order_process (
    production_order_id uuid NOT NULL,
    process_id uuid NOT NULL,
    sequence_no integer NOT NULL,
    speed numeric(18,4),
    CONSTRAINT pk_production_order_process PRIMARY KEY (production_order_id, process_id),
    CONSTRAINT uq_production_order_process_sequence UNIQUE (production_order_id, sequence_no),
    CONSTRAINT fk_production_order_process_order FOREIGN KEY (production_order_id) REFERENCES production.production_order (id),
    CONSTRAINT fk_production_order_process_master FOREIGN KEY (process_id) REFERENCES master_data.process_master (id),
    CONSTRAINT ck_production_order_process_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_production_order_process_speed CHECK (speed IS NULL OR speed > 0)
);

CREATE INDEX ix_production_order_process_master
    ON production.production_order_process (process_id);

CREATE TABLE production.production_woven_weave_type (
    production_order_id uuid NOT NULL,
    weave_type varchar(40) NOT NULL,
    CONSTRAINT pk_production_woven_weave_type PRIMARY KEY (production_order_id, weave_type),
    CONSTRAINT fk_woven_weave_type_config FOREIGN KEY (production_order_id)
        REFERENCES production.production_woven_config (production_order_id),
    CONSTRAINT ck_woven_weave_type CHECK (weave_type IN ('THUONG', 'HAI_DA', 'SATIN', 'SATIN_HAI_DA'))
);

CREATE TABLE production.production_woven_yarn_line (
    id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    line_no integer NOT NULL,
    yarn varchar(120),
    yarn_code varchar(120),
    denier varchar(120),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_production_woven_yarn_line PRIMARY KEY (id),
    CONSTRAINT uq_production_woven_yarn_line UNIQUE (production_order_id, line_no),
    CONSTRAINT fk_woven_yarn_config FOREIGN KEY (production_order_id)
        REFERENCES production.production_woven_config (production_order_id),
    CONSTRAINT ck_woven_yarn_line_no CHECK (line_no > 0)
);

CREATE TRIGGER trg_production_woven_yarn_updated_at
BEFORE UPDATE ON production.production_woven_yarn_line
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE production.production_event (
    id uuid NOT NULL,
    production_order_id uuid NOT NULL,
    event_type varchar(30) NOT NULL,
    actor_user_id uuid NOT NULL,
    reason text,
    payload jsonb,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_production_event PRIMARY KEY (id),
    CONSTRAINT fk_production_event_order FOREIGN KEY (production_order_id) REFERENCES production.production_order (id),
    CONSTRAINT fk_production_event_actor FOREIGN KEY (actor_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_production_event_type CHECK (
        event_type IN ('CREATED', 'GROUPED', 'UNGROUPED', 'CONFIGURED', 'FINISHED')
    )
);

CREATE INDEX ix_production_event_order_time
    ON production.production_event (production_order_id, occurred_at, id);

CREATE TRIGGER trg_production_event_append_only
BEFORE UPDATE OR DELETE ON production.production_event
FOR EACH ROW EXECUTE FUNCTION system.prevent_mutation();

CREATE FUNCTION production.validate_group_membership(p_group_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_count integer;
BEGIN
    IF p_group_id IS NULL OR NOT EXISTS (
        SELECT 1 FROM production.production_group WHERE id = p_group_id
    ) THEN
        RETURN;
    END IF;

    SELECT count(*) INTO v_count
    FROM production.production_order
    WHERE production_group_id = p_group_id;

    IF v_count < 2 THEN
        RAISE EXCEPTION 'Production group % must contain at least two orders', p_group_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION production.check_group_membership_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'production_group' THEN
        PERFORM production.validate_group_membership(COALESCE(NEW.id, OLD.id));
    ELSE
        PERFORM production.validate_group_membership(OLD.production_group_id);
        PERFORM production.validate_group_membership(NEW.production_group_id);
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE CONSTRAINT TRIGGER trg_production_group_minimum_members
AFTER INSERT OR UPDATE ON production.production_group
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION production.check_group_membership_trigger();

CREATE CONSTRAINT TRIGGER trg_production_order_group_members
AFTER INSERT OR UPDATE OR DELETE ON production.production_order
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION production.check_group_membership_trigger();

CREATE FUNCTION production.validate_config_subtype(p_production_order_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    v_kind varchar(20);
    v_status varchar(20);
    v_print_count integer;
    v_woven_count integer;
BEGIN
    SELECT product_kind_snapshot, status
    INTO v_kind, v_status
    FROM production.production_order
    WHERE id = p_production_order_id;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    SELECT count(*) INTO v_print_count
    FROM production.production_print_config
    WHERE production_order_id = p_production_order_id;

    SELECT count(*) INTO v_woven_count
    FROM production.production_woven_config
    WHERE production_order_id = p_production_order_id;

    IF v_print_count + v_woven_count > 1 THEN
        RAISE EXCEPTION 'Production Order % cannot have both PRINT and WOVEN configs', p_production_order_id
            USING ERRCODE = '23514';
    END IF;

    IF v_print_count = 1 AND v_kind <> 'PRINT' THEN
        RAISE EXCEPTION 'PRINT config does not match Production Order kind'
            USING ERRCODE = '23514';
    END IF;

    IF v_woven_count = 1 AND v_kind <> 'WOVEN' THEN
        RAISE EXCEPTION 'WOVEN config does not match Production Order kind'
            USING ERRCODE = '23514';
    END IF;

    IF v_status = 'FINISHED' AND (
        (v_kind = 'PRINT' AND (v_print_count <> 1 OR v_woven_count <> 0))
        OR (v_kind = 'WOVEN' AND (v_woven_count <> 1 OR v_print_count <> 0))
    ) THEN
        RAISE EXCEPTION 'Finished Production Order % requires exactly one matching config', p_production_order_id
            USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE FUNCTION production.check_config_subtype_trigger()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_production_order_id uuid;
BEGIN
    IF TG_TABLE_NAME = 'production_order' THEN
        v_production_order_id := COALESCE(NEW.id, OLD.id);
    ELSE
        v_production_order_id := COALESCE(NEW.production_order_id, OLD.production_order_id);
    END IF;

    PERFORM production.validate_config_subtype(v_production_order_id);
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE CONSTRAINT TRIGGER trg_print_config_subtype
AFTER INSERT OR UPDATE OR DELETE ON production.production_print_config
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION production.check_config_subtype_trigger();

CREATE CONSTRAINT TRIGGER trg_woven_config_subtype
AFTER INSERT OR UPDATE OR DELETE ON production.production_woven_config
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION production.check_config_subtype_trigger();

CREATE CONSTRAINT TRIGGER trg_production_finish_config
AFTER INSERT OR UPDATE ON production.production_order
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION production.check_config_subtype_trigger();
