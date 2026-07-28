CREATE TABLE identity.app_user (
    id uuid NOT NULL,
    kind varchar(10) NOT NULL,
    login_id varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    group_name varchar(120),
    division varchar(120),
    position varchar(120) NOT NULL,
    name varchar(200) NOT NULL,
    sex varchar(20),
    phone varchar(50),
    email varchar(320),
    remark text,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    password_changed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT fk_app_user_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_app_user_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_app_user_kind CHECK (kind IN ('USER', 'STAFF')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ARCHIVED')),
    CONSTRAINT ck_app_user_sex CHECK (sex IS NULL OR sex IN ('MALE', 'FEMALE', 'OTHER')),
    CONSTRAINT ck_app_user_login_id CHECK (btrim(login_id) <> ''),
    CONSTRAINT ck_app_user_password_hash CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_app_user_position CHECK (btrim(position) <> ''),
    CONSTRAINT ck_app_user_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_app_user_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_app_user_login_id_canonical
    ON identity.app_user (lower(btrim(login_id)));
CREATE INDEX ix_app_user_status
    ON identity.app_user (status);

CREATE TRIGGER trg_app_user_updated_at
BEFORE UPDATE ON identity.app_user
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE identity.app_role (
    id uuid NOT NULL,
    code varchar(60) NOT NULL,
    name varchar(120) NOT NULL,
    description text,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid,
    CONSTRAINT pk_app_role PRIMARY KEY (id),
    CONSTRAINT fk_app_role_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_app_role_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_app_role_code CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_app_role_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_app_role_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_app_role_code_canonical
    ON identity.app_role (upper(btrim(code)));

CREATE TRIGGER trg_app_role_updated_at
BEFORE UPDATE ON identity.app_role
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE identity.permission (
    id uuid NOT NULL,
    module_code varchar(60) NOT NULL,
    action_code varchar(60) NOT NULL,
    description text,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_permission PRIMARY KEY (id),
    CONSTRAINT uq_permission_module_action UNIQUE (module_code, action_code),
    CONSTRAINT ck_permission_module CHECK (module_code ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_permission_action CHECK (action_code ~ '^[A-Z][A-Z0-9_]*$')
);

CREATE TABLE identity.user_role (
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    assigned_by uuid NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES identity.app_role (id),
    CONSTRAINT fk_user_role_assigned_by FOREIGN KEY (assigned_by) REFERENCES identity.app_user (id)
);

CREATE INDEX ix_user_role_role
    ON identity.user_role (role_id, user_id);

CREATE TABLE identity.role_permission (
    role_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    granted_by uuid,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES identity.app_role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES identity.permission (id),
    CONSTRAINT fk_role_permission_granted_by FOREIGN KEY (granted_by) REFERENCES identity.app_user (id)
);

CREATE INDEX ix_role_permission_permission
    ON identity.role_permission (permission_id, role_id);

CREATE TABLE identity.user_permission_override (
    user_id uuid NOT NULL,
    permission_id uuid NOT NULL,
    effect varchar(10) NOT NULL,
    reason text,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_user_permission_override PRIMARY KEY (user_id, permission_id),
    CONSTRAINT fk_user_permission_override_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT fk_user_permission_override_permission FOREIGN KEY (permission_id) REFERENCES identity.permission (id),
    CONSTRAINT fk_user_permission_override_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_user_permission_override_effect CHECK (effect IN ('ALLOW', 'DENY'))
);

CREATE TRIGGER trg_user_permission_override_updated_at
BEFORE UPDATE ON identity.user_permission_override
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE identity.login_event (
    id uuid NOT NULL,
    user_id uuid,
    login_id_attempted varchar(100) NOT NULL,
    outcome varchar(30) NOT NULL,
    client_ip inet,
    ip_name_snapshot varchar(200),
    user_agent varchar(500),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_login_event PRIMARY KEY (id),
    CONSTRAINT fk_login_event_user FOREIGN KEY (user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_login_event_outcome CHECK (
        outcome IN ('SUCCESS', 'INVALID_CREDENTIALS', 'DISABLED', 'BLOCKED_IP', 'LOCKED', 'ERROR')
    ),
    CONSTRAINT ck_login_event_login_id CHECK (btrim(login_id_attempted) <> '')
);

CREATE INDEX ix_login_event_user_time
    ON identity.login_event (user_id, occurred_at DESC);
CREATE INDEX ix_login_event_time
    ON identity.login_event (occurred_at);

CREATE TRIGGER trg_login_event_append_only
BEFORE UPDATE OR DELETE ON identity.login_event
FOR EACH ROW EXECUTE FUNCTION system.prevent_mutation();

CREATE TABLE identity.ip_allowlist_entry (
    id uuid NOT NULL,
    network inet NOT NULL,
    name varchar(200) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by uuid NOT NULL,
    CONSTRAINT pk_ip_allowlist_entry PRIMARY KEY (id),
    CONSTRAINT uq_ip_allowlist_network UNIQUE (network),
    CONSTRAINT fk_ip_allowlist_created_by FOREIGN KEY (created_by) REFERENCES identity.app_user (id),
    CONSTRAINT fk_ip_allowlist_updated_by FOREIGN KEY (updated_by) REFERENCES identity.app_user (id),
    CONSTRAINT ck_ip_allowlist_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_ip_allowlist_version CHECK (version >= 0)
);

CREATE TRIGGER trg_ip_allowlist_updated_at
BEFORE UPDATE ON identity.ip_allowlist_entry
FOR EACH ROW EXECUTE FUNCTION system.set_updated_at();

CREATE TABLE audit.audit_event (
    id uuid NOT NULL,
    actor_user_id uuid,
    actor_type varchar(20) NOT NULL,
    action varchar(100) NOT NULL,
    entity_type varchar(100) NOT NULL,
    entity_id uuid NOT NULL,
    request_id varchar(120),
    reason text,
    before_data jsonb,
    after_data jsonb,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_user_id) REFERENCES identity.app_user (id),
    CONSTRAINT ck_audit_event_actor_type CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT ck_audit_event_actor CHECK (
        (actor_type = 'USER' AND actor_user_id IS NOT NULL)
        OR (actor_type = 'SYSTEM')
    ),
    CONSTRAINT ck_audit_event_action CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_event_entity_type CHECK (btrim(entity_type) <> '')
);

CREATE INDEX ix_audit_event_entity_time
    ON audit.audit_event (entity_type, entity_id, occurred_at);
CREATE INDEX ix_audit_event_actor_time
    ON audit.audit_event (actor_user_id, occurred_at DESC);
CREATE INDEX ix_audit_event_time
    ON audit.audit_event (occurred_at);

CREATE TRIGGER trg_audit_event_append_only
BEFORE UPDATE OR DELETE ON audit.audit_event
FOR EACH ROW EXECUTE FUNCTION system.prevent_mutation();
