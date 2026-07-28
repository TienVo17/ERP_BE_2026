-- PostgreSQL 15+ baseline. The application supplies UUID values.

CREATE SCHEMA system;
CREATE SCHEMA identity;
CREATE SCHEMA audit;
CREATE SCHEMA master_data;
CREATE SCHEMA sales;
CREATE SCHEMA production;
CREATE SCHEMA inventory;
CREATE SCHEMA delivery;

CREATE FUNCTION system.set_updated_at()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END;
$$;

CREATE FUNCTION system.prevent_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not allowed', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TABLE system.document_number_counter (
    document_type varchar(20) NOT NULL,
    document_year smallint NOT NULL,
    last_value bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_document_number_counter PRIMARY KEY (document_type, document_year),
    CONSTRAINT ck_document_number_type CHECK (document_type IN ('SALES_ORDER', 'PRODUCTION', 'DELIVERY')),
    CONSTRAINT ck_document_number_year CHECK (document_year BETWEEN 2000 AND 9999),
    CONSTRAINT ck_document_number_value CHECK (last_value >= 0)
);

CREATE FUNCTION system.next_document_number(
    p_document_type varchar,
    p_prefix varchar,
    p_allocation_year smallint DEFAULT EXTRACT(YEAR FROM CURRENT_DATE)::smallint
)
RETURNS varchar
LANGUAGE plpgsql
AS $$
DECLARE
    v_value bigint;
BEGIN
    IF p_document_type NOT IN ('SALES_ORDER', 'PRODUCTION', 'DELIVERY') THEN
        RAISE EXCEPTION 'Unsupported document type: %', p_document_type
            USING ERRCODE = '22023';
    END IF;

    IF p_prefix !~ '^[A-Z]{2,4}$' THEN
        RAISE EXCEPTION 'Invalid document prefix: %', p_prefix
            USING ERRCODE = '22023';
    END IF;

    INSERT INTO system.document_number_counter (
        document_type,
        document_year,
        last_value,
        updated_at
    )
    VALUES (p_document_type, p_allocation_year, 1, clock_timestamp())
    ON CONFLICT (document_type, document_year)
    DO UPDATE SET
        last_value = system.document_number_counter.last_value + 1,
        updated_at = clock_timestamp()
    RETURNING last_value INTO v_value;

    RETURN format('%s-%s-%s', p_prefix, p_allocation_year, lpad(v_value::text, 6, '0'));
END;
$$;

CREATE TABLE system.idempotency_record (
    id uuid NOT NULL,
    scope varchar(60) NOT NULL,
    idempotency_key varchar(120) NOT NULL,
    request_hash varchar(128) NOT NULL,
    resource_type varchar(60),
    resource_id uuid,
    status varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_scope_key UNIQUE (scope, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_idempotency_resource_pair CHECK (
        (resource_type IS NULL AND resource_id IS NULL)
        OR (resource_type IS NOT NULL AND resource_id IS NOT NULL)
    )
);

CREATE INDEX ix_idempotency_expiry
    ON system.idempotency_record (expires_at);
