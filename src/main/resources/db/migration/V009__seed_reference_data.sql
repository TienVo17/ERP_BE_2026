-- A disabled SYSTEM principal owns migration-seeded reference rows and cannot authenticate.
ALTER TABLE identity.app_user
    DROP CONSTRAINT ck_app_user_kind,
    DROP CONSTRAINT ck_app_user_password_hash,
    ADD CONSTRAINT ck_app_user_kind CHECK (kind IN ('USER', 'STAFF', 'SYSTEM')),
    ADD CONSTRAINT ck_app_user_password_hash CHECK (
        (kind = 'SYSTEM' AND password_hash = '!NO_LOGIN!')
        OR (kind IN ('USER', 'STAFF') AND btrim(password_hash) <> '')
    );

INSERT INTO identity.app_user (
    id,
    kind,
    login_id,
    password_hash,
    position,
    name,
    status,
    password_changed_at,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'SYSTEM',
    '__system__',
    '!NO_LOGIN!',
    'SYSTEM',
    'System Migration Principal',
    'DISABLED',
    clock_timestamp(),
    clock_timestamp(),
    clock_timestamp()
);

INSERT INTO master_data.currency (code, name, active) VALUES
    ('USD', 'US Dollar', true),
    ('VND', 'Vietnamese Dong', true),
    ('WON', 'South Korean Won', true);

INSERT INTO master_data.uom (
    id, code, name, status, created_by, updated_by
)
SELECT
    seed.id::uuid,
    seed.code,
    seed.name,
    'ACTIVE',
    '00000000-0000-0000-0000-000000000001'::uuid,
    '00000000-0000-0000-0000-000000000001'::uuid
FROM (VALUES
    ('10000000-0000-0000-0000-000000000001', 'CON', 'Unit'),
    ('10000000-0000-0000-0000-000000000002', 'EA', 'Each'),
    ('10000000-0000-0000-0000-000000000003', 'KG', 'Kilogram'),
    ('10000000-0000-0000-0000-000000000004', 'M', 'Meter'),
    ('10000000-0000-0000-0000-000000000005', 'MT', 'Metric Ton'),
    ('10000000-0000-0000-0000-000000000006', 'PC', 'Piece'),
    ('10000000-0000-0000-0000-000000000007', 'PCS', 'Pieces'),
    ('10000000-0000-0000-0000-000000000008', 'ROLL', 'Roll'),
    ('10000000-0000-0000-0000-000000000009', 'SET', 'Set'),
    ('10000000-0000-0000-0000-000000000010', 'YARD', 'Yard'),
    ('10000000-0000-0000-0000-000000000011', 'YD', 'Yard'),
    ('10000000-0000-0000-0000-000000000012', 'YDS', 'Yards')
) AS seed(id, code, name);

INSERT INTO identity.app_role (
    id, code, name, description, active
)
VALUES
    ('20000000-0000-0000-0000-000000000001', 'SALE', 'Sale', 'Sale-team baseline role', true),
    ('20000000-0000-0000-0000-000000000002', 'ADMIN', 'Administrator', 'ERP administrator role', true);

INSERT INTO identity.permission (id, module_code, action_code, description)
SELECT seed.id::uuid, seed.module_code, seed.action_code, seed.description
FROM (VALUES
    ('30000000-0000-0000-0000-000000000001', 'RAW_MATERIAL', 'VIEW', 'View Raw Material master'),
    ('30000000-0000-0000-0000-000000000002', 'RAW_MATERIAL', 'CREATE', 'Create Raw Material master'),
    ('30000000-0000-0000-0000-000000000003', 'RAW_MATERIAL', 'UPDATE', 'Update Raw Material master'),
    ('30000000-0000-0000-0000-000000000004', 'RAW_MATERIAL', 'ARCHIVE', 'Archive Raw Material master'),
    ('30000000-0000-0000-0000-000000000005', 'FINISHED_GOODS', 'VIEW', 'View Finished Good master'),
    ('30000000-0000-0000-0000-000000000006', 'FINISHED_GOODS', 'CREATE', 'Create Finished Good master'),
    ('30000000-0000-0000-0000-000000000007', 'FINISHED_GOODS', 'UPDATE', 'Update Finished Good master'),
    ('30000000-0000-0000-0000-000000000008', 'FINISHED_GOODS', 'ARCHIVE', 'Archive Finished Good master'),
    ('30000000-0000-0000-0000-000000000009', 'CUSTOMER', 'VIEW', 'View Customer master'),
    ('30000000-0000-0000-0000-000000000010', 'CUSTOMER', 'CREATE', 'Create Customer master'),
    ('30000000-0000-0000-0000-000000000011', 'CUSTOMER', 'UPDATE', 'Update Customer master'),
    ('30000000-0000-0000-0000-000000000012', 'CUSTOMER', 'ARCHIVE', 'Archive Customer master'),
    ('30000000-0000-0000-0000-000000000013', 'SUPPLIER', 'VIEW', 'View Supplier master'),
    ('30000000-0000-0000-0000-000000000014', 'SUPPLIER', 'CREATE', 'Create Supplier master'),
    ('30000000-0000-0000-0000-000000000015', 'SUPPLIER', 'UPDATE', 'Update Supplier master'),
    ('30000000-0000-0000-0000-000000000016', 'SUPPLIER', 'ARCHIVE', 'Archive Supplier master'),
    ('30000000-0000-0000-0000-000000000017', 'PROCESS', 'VIEW', 'View Process master'),
    ('30000000-0000-0000-0000-000000000018', 'PROCESS', 'CREATE', 'Create Process master'),
    ('30000000-0000-0000-0000-000000000019', 'PROCESS', 'UPDATE', 'Update Process master'),
    ('30000000-0000-0000-0000-000000000020', 'PROCESS', 'ARCHIVE', 'Archive Process master'),
    ('30000000-0000-0000-0000-000000000021', 'ETC', 'VIEW', 'View UOM and Exchange Rate'),
    ('30000000-0000-0000-0000-000000000022', 'ETC', 'CREATE', 'Create UOM and Exchange Rate'),
    ('30000000-0000-0000-0000-000000000023', 'ETC', 'UPDATE', 'Update UOM and Exchange Rate'),
    ('30000000-0000-0000-0000-000000000024', 'ETC', 'ARCHIVE', 'Archive UOM and Exchange Rate'),
    ('30000000-0000-0000-0000-000000000025', 'BUYER_ORDER', 'VIEW', 'View Buyer Orders'),
    ('30000000-0000-0000-0000-000000000026', 'BUYER_ORDER', 'CREATE', 'Create Buyer Orders'),
    ('30000000-0000-0000-0000-000000000027', 'BUYER_ORDER', 'UPDATE', 'Update draft Buyer Orders'),
    ('30000000-0000-0000-0000-000000000028', 'BUYER_ORDER', 'CONFIRM', 'Confirm Buyer Orders'),
    ('30000000-0000-0000-0000-000000000029', 'BUYER_ORDER', 'REOPEN', 'Reopen Buyer Orders'),
    ('30000000-0000-0000-0000-000000000030', 'PRODUCTION', 'VIEW', 'View Production Orders'),
    ('30000000-0000-0000-0000-000000000031', 'PRODUCTION', 'GROUP', 'Group and ungroup Production Orders'),
    ('30000000-0000-0000-0000-000000000032', 'PRODUCTION', 'CONFIGURE', 'Configure Production Orders'),
    ('30000000-0000-0000-0000-000000000033', 'PRODUCTION', 'FINISH', 'Finish Production Orders'),
    ('30000000-0000-0000-0000-000000000034', 'DELIVERY', 'VIEW', 'View Delivery Notes'),
    ('30000000-0000-0000-0000-000000000035', 'DELIVERY', 'CREATE', 'Create Delivery drafts'),
    ('30000000-0000-0000-0000-000000000036', 'DELIVERY', 'POST', 'Post Delivery Notes'),
    ('30000000-0000-0000-0000-000000000037', 'DELIVERY', 'REVERSE', 'Reverse posted Delivery Notes'),
    ('30000000-0000-0000-0000-000000000038', 'DELIVERY', 'PRINT', 'Print Delivery Notes'),
    ('30000000-0000-0000-0000-000000000039', 'DELIVERY', 'EXPORT', 'Export Delivery data'),
    ('30000000-0000-0000-0000-000000000040', 'STOCK', 'VIEW', 'View Finished Good stock'),
    ('30000000-0000-0000-0000-000000000041', 'STOCK', 'RETURN', 'Record customer returns'),
    ('30000000-0000-0000-0000-000000000042', 'STOCK', 'DISPOSE', 'Dispose Finished Good stock'),
    ('30000000-0000-0000-0000-000000000043', 'ADMIN', 'MANAGE_USERS', 'Manage users and profiles'),
    ('30000000-0000-0000-0000-000000000044', 'ADMIN', 'MANAGE_ROLES', 'Manage roles and permission overrides'),
    ('30000000-0000-0000-0000-000000000045', 'ADMIN', 'MANAGE_ALLOWLIST', 'Manage the IP allowlist'),
    ('30000000-0000-0000-0000-000000000046', 'ADMIN', 'VIEW_AUDIT', 'View security and business audit')
) AS seed(id, module_code, action_code, description);

-- ADMIN receives all seeded permissions. SALE remains default-deny until business owners approve its baseline grants.
INSERT INTO identity.role_permission (role_id, permission_id, granted_by)
SELECT
    '20000000-0000-0000-0000-000000000002'::uuid,
    p.id,
    '00000000-0000-0000-0000-000000000001'::uuid
FROM identity.permission p;
