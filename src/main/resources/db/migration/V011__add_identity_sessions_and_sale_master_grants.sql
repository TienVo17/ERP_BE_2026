ALTER TABLE identity.app_user
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
    ADD COLUMN password_generation bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_app_user_password_generation CHECK (password_generation >= 0);

CREATE TABLE identity.auth_session (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    idle_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoked_reason varchar(120),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_refreshed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    user_agent varchar(500),
    client_ip inet,
    CONSTRAINT pk_auth_session PRIMARY KEY (id),
    CONSTRAINT fk_auth_session_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_auth_session_expiry CHECK (
        created_at <= last_refreshed_at
        AND last_refreshed_at <= idle_expires_at
        AND idle_expires_at <= absolute_expires_at
    ),
    CONSTRAINT ck_auth_session_revocation CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL AND btrim(revoked_reason) <> '')
    )
);

CREATE INDEX ix_auth_session_user_expiry
    ON identity.auth_session (user_id, absolute_expires_at, idle_expires_at);
CREATE INDEX ix_auth_session_active_expiry
    ON identity.auth_session (absolute_expires_at, idle_expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE identity.refresh_token (
    id uuid NOT NULL,
    auth_session_id uuid NOT NULL,
    token_hash bytea NOT NULL,
    parent_token_id uuid,
    status varchar(10) NOT NULL DEFAULT 'ACTIVE',
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_refresh_token PRIMARY KEY (id),
    CONSTRAINT uq_refresh_token_session_id UNIQUE (auth_session_id, id),
    CONSTRAINT fk_refresh_token_session FOREIGN KEY (auth_session_id) REFERENCES identity.auth_session (id),
    CONSTRAINT fk_refresh_token_parent FOREIGN KEY (auth_session_id, parent_token_id)
        REFERENCES identity.refresh_token (auth_session_id, id),
    CONSTRAINT ck_refresh_token_parent CHECK (parent_token_id IS NULL OR parent_token_id <> id),
    CONSTRAINT ck_refresh_token_hash_length CHECK (octet_length(token_hash) = 32),
    CONSTRAINT ck_refresh_token_status CHECK (status IN ('ACTIVE', 'USED', 'REVOKED')),
    CONSTRAINT ck_refresh_token_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_token_lifecycle CHECK (
        (status = 'ACTIVE' AND used_at IS NULL AND revoked_at IS NULL)
        OR (status = 'USED' AND used_at IS NOT NULL AND used_at >= created_at AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_at >= created_at)
    )
);

CREATE UNIQUE INDEX uq_refresh_token_hash
    ON identity.refresh_token (token_hash);
CREATE INDEX ix_refresh_token_session_status_expiry
    ON identity.refresh_token (auth_session_id, status, expires_at);
CREATE INDEX ix_refresh_token_parent
    ON identity.refresh_token (parent_token_id)
    WHERE parent_token_id IS NOT NULL;

INSERT INTO identity.role_permission (role_id, permission_id, granted_by)
SELECT
    '20000000-0000-0000-0000-000000000001'::uuid,
    p.id,
    '00000000-0000-0000-0000-000000000001'::uuid
FROM identity.permission p
WHERE (p.module_code, p.action_code) IN (
    ('RAW_MATERIAL', 'VIEW'),
    ('RAW_MATERIAL', 'CREATE'),
    ('RAW_MATERIAL', 'UPDATE'),
    ('FINISHED_GOODS', 'VIEW'),
    ('FINISHED_GOODS', 'CREATE'),
    ('FINISHED_GOODS', 'UPDATE'),
    ('CUSTOMER', 'VIEW'),
    ('CUSTOMER', 'CREATE'),
    ('CUSTOMER', 'UPDATE'),
    ('SUPPLIER', 'VIEW'),
    ('SUPPLIER', 'CREATE'),
    ('SUPPLIER', 'UPDATE'),
    ('PROCESS', 'VIEW'),
    ('PROCESS', 'CREATE'),
    ('PROCESS', 'UPDATE'),
    ('ETC', 'VIEW'),
    ('ETC', 'CREATE'),
    ('ETC', 'UPDATE')
)
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- V010 cannot grant privileges on later objects. Every forward migration must
-- explicitly apply least privilege to the configured runtime role it extends.
DO $$
DECLARE
    v_runtime_role text := current_setting('erp.runtime_role', true);
BEGIN
    REVOKE ALL ON identity.auth_session, identity.refresh_token FROM PUBLIC;

    IF v_runtime_role IS NULL OR btrim(v_runtime_role) = '' THEN
        RAISE NOTICE 'erp.runtime_role is not configured; V011 runtime grants were not created';
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_runtime_role) THEN
        RAISE EXCEPTION 'Configured ERP runtime role % does not exist', v_runtime_role
            USING ERRCODE = '42704';
    END IF;

    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE ON identity.auth_session, identity.refresh_token TO %I',
        v_runtime_role
    );
END;
$$;
