CREATE TABLE master_data.currency (
    code char(3) NOT NULL,
    name varchar(100) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT pk_currency PRIMARY KEY (code),
    CONSTRAINT ck_currency_code CHECK (code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_currency_name CHECK (btrim(name) <> '')
);

CREATE TABLE master_data.media_asset (
    id uuid NOT NULL,
    storage_key varchar(500) NOT NULL,
    original_filename varchar(255) NOT NULL,
    mime_type varchar(100) NOT NULL,
    byte_size bigint NOT NULL,
    sha256 char(64) NOT NULL,
    uploaded_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_media_asset PRIMARY KEY (id),
    CONSTRAINT uq_media_asset_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_media_asset_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_media_asset_filename CHECK (btrim(original_filename) <> ''),
    CONSTRAINT ck_media_asset_mime CHECK (mime_type IN ('image/png', 'image/jpeg', 'image/webp')),
    CONSTRAINT ck_media_asset_size CHECK (byte_size > 0),
    CONSTRAINT ck_media_asset_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

ALTER TABLE identity.app_user
    ADD COLUMN avatar_asset_id uuid,
    ADD CONSTRAINT fk_app_user_avatar FOREIGN KEY (avatar_asset_id) REFERENCES master_data.media_asset (id);

CREATE TABLE master_data.uom (
    id uuid NOT NULL,
    code varchar(30) NOT NULL,
    name varchar(100),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_uom PRIMARY KEY (id),
    CONSTRAINT fk_uom_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_uom_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_uom_code CHECK (btrim(code) <> ''),
    CONSTRAINT ck_uom_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_uom_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_uom_code_canonical
    ON master_data.uom (upper(btrim(code)));

CREATE TRIGGER trg_uom_updated_at
BEFORE UPDATE ON master_data.uom
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE master_data.monthly_exchange_rate (
    id uuid NOT NULL,
    effective_month date NOT NULL,
    vnd_usd_rate numeric(18,6) NOT NULL,
    won_usd_rate numeric(18,6) NOT NULL,
    source varchar(120),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_monthly_exchange_rate PRIMARY KEY (id),
    CONSTRAINT uq_monthly_exchange_rate_month UNIQUE (effective_month),
    CONSTRAINT fk_exchange_rate_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_exchange_rate_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_exchange_rate_first_day CHECK (EXTRACT(DAY FROM effective_month) = 1),
    CONSTRAINT ck_exchange_rate_vnd CHECK (vnd_usd_rate > 0),
    CONSTRAINT ck_exchange_rate_won CHECK (won_usd_rate > 0),
    CONSTRAINT ck_exchange_rate_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_exchange_rate_version CHECK (version >= 0)
);

CREATE TRIGGER trg_monthly_exchange_rate_updated_at
BEFORE UPDATE ON master_data.monthly_exchange_rate
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE master_data.customer (
    id uuid NOT NULL,
    short_name varchar(50) NOT NULL,
    name varchar(200) NOT NULL,
    address text,
    telephone varchar(50),
    currency_code char(3) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_customer PRIMARY KEY (id),
    CONSTRAINT fk_customer_currency FOREIGN KEY (currency_code) REFERENCES master_data.currency (code),
    CONSTRAINT fk_customer_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_customer_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_customer_short_name CHECK (btrim(short_name) <> ''),
    CONSTRAINT ck_customer_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_customer_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_customer_short_name_canonical
    ON master_data.customer (upper(btrim(short_name)));
CREATE INDEX ix_customer_status
    ON master_data.customer (status);

CREATE TRIGGER trg_customer_updated_at
BEFORE UPDATE ON master_data.customer
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE FUNCTION master_data.prevent_customer_short_name_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF upper(btrim(NEW.short_name)) IS DISTINCT FROM upper(btrim(OLD.short_name)) THEN
        RAISE EXCEPTION 'Customer short name is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customer_short_name_immutable
BEFORE UPDATE ON master_data.customer
FOR EACH ROW EXECUTE FUNCTION master_data.prevent_customer_short_name_change();

CREATE TABLE master_data.customer_contact (
    id uuid NOT NULL,
    customer_id uuid NOT NULL,
    division varchar(120),
    name varchar(200) NOT NULL,
    telephone varchar(50),
    email varchar(320),
    remark text,
    is_default boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_customer_contact PRIMARY KEY (id),
    CONSTRAINT uq_customer_contact_id_customer UNIQUE (id, customer_id),
    CONSTRAINT fk_customer_contact_customer FOREIGN KEY (customer_id) REFERENCES master_data.customer (id),
    CONSTRAINT fk_customer_contact_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_customer_contact_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_customer_contact_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_customer_contact_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_customer_contact_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_customer_contact_active_default
    ON master_data.customer_contact (customer_id)
    WHERE is_default AND status = 'ACTIVE';
CREATE INDEX ix_customer_contact_customer_status
    ON master_data.customer_contact (customer_id, status);

CREATE TRIGGER trg_customer_contact_updated_at
BEFORE UPDATE ON master_data.customer_contact
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE master_data.supplier (
    id uuid NOT NULL,
    name varchar(200) NOT NULL,
    address text,
    telephone varchar(50),
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_supplier PRIMARY KEY (id),
    CONSTRAINT fk_supplier_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_supplier_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_supplier_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_supplier_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_supplier_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_supplier_name_canonical
    ON master_data.supplier (upper(btrim(name)));

CREATE TRIGGER trg_supplier_updated_at
BEFORE UPDATE ON master_data.supplier
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE master_data.supplier_contact (
    id uuid NOT NULL,
    supplier_id uuid NOT NULL,
    division varchar(120),
    name varchar(200) NOT NULL,
    telephone varchar(50),
    email varchar(320),
    remark text,
    is_default boolean NOT NULL DEFAULT false,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_supplier_contact PRIMARY KEY (id),
    CONSTRAINT uq_supplier_contact_id_supplier UNIQUE (id, supplier_id),
    CONSTRAINT fk_supplier_contact_supplier FOREIGN KEY (supplier_id) REFERENCES master_data.supplier (id),
    CONSTRAINT fk_supplier_contact_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_supplier_contact_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_supplier_contact_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_supplier_contact_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_supplier_contact_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_supplier_contact_active_default
    ON master_data.supplier_contact (supplier_id)
    WHERE is_default AND status = 'ACTIVE';
CREATE INDEX ix_supplier_contact_supplier_status
    ON master_data.supplier_contact (supplier_id, status);

CREATE TRIGGER trg_supplier_contact_updated_at
BEFORE UPDATE ON master_data.supplier_contact
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE master_data.process_master (
    id uuid NOT NULL,
    name varchar(150) NOT NULL,
    sequence_no integer NOT NULL,
    qr_value varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_process_master PRIMARY KEY (id),
    CONSTRAINT uq_process_master_qr UNIQUE (qr_value),
    CONSTRAINT fk_process_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_process_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_process_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_process_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_process_qr CHECK (btrim(qr_value) <> ''),
    CONSTRAINT ck_process_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_process_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_process_name_canonical
    ON master_data.process_master (upper(btrim(name)));

CREATE TRIGGER trg_process_master_updated_at
BEFORE UPDATE ON master_data.process_master
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE FUNCTION master_data.prevent_process_qr_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.qr_value IS DISTINCT FROM OLD.qr_value THEN
        RAISE EXCEPTION 'Process QR value is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_process_qr_immutable
BEFORE UPDATE ON master_data.process_master
FOR EACH ROW EXECUTE FUNCTION master_data.prevent_process_qr_change();

CREATE TABLE master_data.raw_material (
    id uuid NOT NULL,
    category varchar(120),
    code varchar(80) NOT NULL,
    name varchar(200) NOT NULL,
    specification text,
    size varchar(120),
    color varchar(120),
    uom_id uuid NOT NULL,
    reference_price numeric(18,6),
    currency_code char(3) NOT NULL,
    supplier_id uuid,
    safety_stock_qty numeric(18,4),
    remark text,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_raw_material PRIMARY KEY (id),
    CONSTRAINT fk_raw_material_uom FOREIGN KEY (uom_id) REFERENCES master_data.uom (id),
    CONSTRAINT fk_raw_material_currency FOREIGN KEY (currency_code) REFERENCES master_data.currency (code),
    CONSTRAINT fk_raw_material_supplier FOREIGN KEY (supplier_id) REFERENCES master_data.supplier (id),
    CONSTRAINT fk_raw_material_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_raw_material_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_raw_material_code CHECK (btrim(code) <> ''),
    CONSTRAINT ck_raw_material_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_raw_material_price CHECK (reference_price IS NULL OR reference_price >= 0),
    CONSTRAINT ck_raw_material_safety_stock CHECK (safety_stock_qty IS NULL OR safety_stock_qty >= 0),
    CONSTRAINT ck_raw_material_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_raw_material_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_raw_material_code_canonical
    ON master_data.raw_material (upper(btrim(code)));

CREATE TRIGGER trg_raw_material_updated_at
BEFORE UPDATE ON master_data.raw_material
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE master_data.finished_good (
    id uuid NOT NULL,
    product_kind varchar(20) NOT NULL,
    style_no varchar(120) NOT NULL,
    name varchar(200) NOT NULL,
    size varchar(120),
    color varchar(120),
    uom_id uuid NOT NULL,
    reference_price numeric(18,6),
    currency_code char(3),
    image_asset_id uuid,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_finished_good PRIMARY KEY (id),
    CONSTRAINT fk_finished_good_uom FOREIGN KEY (uom_id) REFERENCES master_data.uom (id),
    CONSTRAINT fk_finished_good_currency FOREIGN KEY (currency_code) REFERENCES master_data.currency (code),
    CONSTRAINT fk_finished_good_image FOREIGN KEY (image_asset_id) REFERENCES master_data.media_asset (id),
    CONSTRAINT fk_finished_good_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_finished_good_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_finished_good_kind CHECK (product_kind IN ('PRINT', 'WOVEN')),
    CONSTRAINT ck_finished_good_style CHECK (btrim(style_no) <> ''),
    CONSTRAINT ck_finished_good_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_finished_good_price CHECK (reference_price IS NULL OR reference_price >= 0),
    CONSTRAINT ck_finished_good_price_currency CHECK (
        (reference_price IS NULL AND currency_code IS NULL)
        OR (reference_price IS NOT NULL AND currency_code IS NOT NULL)
    ),
    CONSTRAINT ck_finished_good_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_finished_good_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_finished_good_canonical
    ON master_data.finished_good (
        product_kind,
        upper(btrim(style_no)),
        upper(btrim(name)),
        upper(COALESCE(NULLIF(btrim(size), ''), '<NULL>')),
        upper(COALESCE(NULLIF(btrim(color), ''), '<NULL>'))
    );

CREATE TRIGGER trg_finished_good_updated_at
BEFORE UPDATE ON master_data.finished_good
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();
