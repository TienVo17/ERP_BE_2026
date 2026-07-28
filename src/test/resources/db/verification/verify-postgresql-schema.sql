\set ON_ERROR_STOP on

BEGIN;

DO $$
DECLARE
    v_missing integer;
BEGIN
    SELECT count(*) INTO v_missing
    FROM (VALUES
        ('system'),
        ('identity'),
        ('audit'),
        ('master_data'),
        ('sales'),
        ('production'),
        ('inventory'),
        ('delivery')
    ) AS expected(schema_name)
    WHERE NOT EXISTS (
        SELECT 1 FROM pg_namespace n WHERE n.nspname = expected.schema_name
    );

    IF v_missing <> 0 THEN
        RAISE EXCEPTION '% expected schemas are missing', v_missing;
    END IF;
END;
$$;

DO $$
DECLARE
    v_currency_count integer;
    v_uom_count integer;
    v_permission_count integer;
BEGIN
    SELECT count(*) INTO v_currency_count FROM master_data.currency;
    SELECT count(*) INTO v_uom_count FROM master_data.uom;
    SELECT count(*) INTO v_permission_count FROM identity.permission;

    IF v_currency_count <> 3 THEN
        RAISE EXCEPTION 'Expected 3 seeded currencies, found %', v_currency_count;
    END IF;

    IF v_uom_count <> 12 THEN
        RAISE EXCEPTION 'Expected 12 seeded UOM rows, found %', v_uom_count;
    END IF;

    IF v_permission_count <> 46 THEN
        RAISE EXCEPTION 'Expected 46 business permissions, found %', v_permission_count;
    END IF;
END;
$$;

DO $$
DECLARE
    v_first varchar;
    v_second varchar;
BEGIN
    v_first := system.next_document_number('SALES_ORDER', 'SO', 2099::smallint);
    v_second := system.next_document_number('SALES_ORDER', 'SO', 2099::smallint);

    IF v_first <> 'SO-2099-000001' OR v_second <> 'SO-2099-000002' THEN
        RAISE EXCEPTION 'Document-number sequence returned %, %', v_first, v_second;
    END IF;
END;
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.views
        WHERE table_schema = 'delivery'
          AND table_name = 'debit_note_projection'
    ) THEN
        RAISE EXCEPTION 'Debit projection is missing';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.views
        WHERE table_schema = 'inventory'
          AND table_name = 'stock_ledger_reconciliation'
    ) THEN
        RAISE EXCEPTION 'Stock reconciliation view is missing';
    END IF;
END;
$$;

DO $$
DECLARE
    v_user uuid := '90000000-0000-0000-0000-000000000001';
    v_customer uuid := '90000000-0000-0000-0000-000000000002';
    v_uom uuid := '10000000-0000-0000-0000-000000000007';
    v_order uuid := '90000000-0000-0000-0000-000000000003';
    v_item uuid := '90000000-0000-0000-0000-000000000004';
BEGIN
    INSERT INTO identity.app_user (
        id, kind, login_id, password_hash, position, name, status
    ) VALUES (
        v_user, 'USER', 'schema-verifier', '$argon2id$verification-placeholder', 'QA', 'Schema Verifier', 'ACTIVE'
    );

    INSERT INTO master_data.customer (
        id, short_name, name, currency_code, created_by, updated_by
    ) VALUES (
        v_customer, 'VERIFY', 'Verification Customer', 'USD', v_user, v_user
    );

    INSERT INTO sales.buyer_order (
        id,
        sys_po_no,
        order_type,
        customer_id,
        customer_name_snapshot,
        customer_short_name_snapshot,
        pic_source,
        pic_name_snapshot,
        buyer_po,
        po_date,
        delivery_date,
        created_by,
        updated_by
    ) VALUES (
        v_order,
        'SO-2099-999999',
        'VERIFY',
        v_customer,
        'Verification Customer',
        'VERIFY',
        'CUSTOM',
        'Verification PIC',
        'BUYER-VERIFY',
        DATE '2099-01-01',
        DATE '2099-01-02',
        v_user,
        v_user
    );

    INSERT INTO sales.buyer_order_item (
        id,
        buyer_order_id,
        line_no,
        is_custom,
        product_kind_snapshot,
        style_no_snapshot,
        name_snapshot,
        uom_id,
        uom_code_snapshot,
        order_qty,
        use_stock_qty,
        production_qty,
        unit_price,
        currency_code,
        amount,
        created_by,
        updated_by
    ) VALUES (
        v_item,
        v_order,
        1,
        true,
        'PRINT',
        'VERIFY-STYLE',
        'Verification Item',
        v_uom,
        'PCS',
        10,
        0,
        10,
        1.250000,
        'USD',
        12.50,
        v_user,
        v_user
    );

    BEGIN
        INSERT INTO sales.buyer_order_item (
            id,
            buyer_order_id,
            line_no,
            is_custom,
            product_kind_snapshot,
            style_no_snapshot,
            name_snapshot,
            uom_id,
            uom_code_snapshot,
            order_qty,
            use_stock_qty,
            production_qty,
            unit_price,
            currency_code,
            amount,
            created_by,
            updated_by
        ) VALUES (
            '90000000-0000-0000-0000-000000000005',
            v_order,
            2,
            true,
            'PRINT',
            'INVALID-USE-STOCK',
            'Invalid Use Stock',
            v_uom,
            'PCS',
            10,
            1,
            9,
            1,
            'USD',
            10,
            v_user,
            v_user
        );

        RAISE EXCEPTION 'Expected positive USE STOCK QTY to be rejected';
    EXCEPTION
        WHEN check_violation THEN
            NULL;
    END;
END;
$$;

DO $$
BEGIN
    BEGIN
        INSERT INTO inventory.stock_movement (
            id, stock_position_id, movement_type, quantity_signed, balance_after,
            business_date, source_type, source_id, idempotency_key, created_by
        ) VALUES (
            '90000000-0000-0000-0000-000000000006',
            '90000000-0000-0000-0000-000000000007',
            'DELIVERY',
            -1,
            0,
            CURRENT_DATE,
            'MANUAL',
            '90000000-0000-0000-0000-000000000008',
            'manual-delivery-bypass',
            '00000000-0000-0000-0000-000000000001'
        );
        RAISE EXCEPTION 'Expected noncanonical DELIVERY source semantics to be rejected';
    EXCEPTION
        WHEN check_violation THEN
            NULL;
    END;
END;
$$;

DO $$
DECLARE
    v_user uuid := '90000000-0000-0000-0000-000000000010';
    v_customer uuid := '90000000-0000-0000-0000-000000000011';
    v_order uuid := '90000000-0000-0000-0000-000000000012';
    v_item uuid := '90000000-0000-0000-0000-000000000013';
    v_production uuid := '90000000-0000-0000-0000-000000000014';
BEGIN
    INSERT INTO identity.app_user (id, kind, login_id, password_hash, position, name, status)
    VALUES (v_user, 'USER', 'downstream-verifier', '$argon2id$verification-placeholder', 'QA', 'Downstream Verifier', 'ACTIVE');

    INSERT INTO master_data.customer (id, short_name, name, currency_code, created_by, updated_by)
    VALUES (v_customer, 'DOWNSTREAM', 'Downstream Customer', 'USD', v_user, v_user);

    INSERT INTO sales.buyer_order (
        id, sys_po_no, order_type, customer_id, customer_name_snapshot,
        customer_short_name_snapshot, pic_source, pic_name_snapshot, buyer_po,
        po_date, delivery_date, status, created_by, updated_by
    ) VALUES (
        v_order, 'SO-2099-999998', 'VERIFY', v_customer, 'Downstream Customer',
        'DOWNSTREAM', 'CUSTOM', 'PIC', 'BUYER-DOWNSTREAM', DATE '2099-01-01',
        DATE '2099-01-02', 'STANDBY', v_user, v_user
    );

    INSERT INTO sales.buyer_order_item (
        id, buyer_order_id, line_no, is_custom, product_kind_snapshot,
        style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
        use_stock_qty, production_qty, unit_price, currency_code, amount,
        created_by, updated_by
    ) VALUES (
        v_item, v_order, 1, true, 'PRINT', 'DOWNSTREAM', 'Downstream Item',
        '10000000-0000-0000-0000-000000000007', 'PCS', 10, 0, 10, 1, 'USD', 10,
        v_user, v_user
    );

    UPDATE sales.buyer_order SET status = 'CONFIRMED', updated_by = v_user WHERE id = v_order;

    INSERT INTO production.production_order (
        id, production_no, buyer_order_item_id, buyer_order_id,
        product_kind_snapshot, product_no, qr_value, planned_qty, status,
        created_by, updated_by
    ) VALUES (
        v_production, 'PR-2099-999998', v_item, v_order,
        'PRINT', 'DOWNSTREAM', 'QR-DOWNSTREAM', 10, 'OPEN', v_user, v_user
    );

    INSERT INTO production.production_print_config (production_order_id, updated_by)
    VALUES (v_production, v_user);

    UPDATE production.production_order
    SET status = 'FINISHED', produced_qty = 10, finished_at = clock_timestamp(),
        finished_by = v_user, updated_by = v_user
    WHERE id = v_production;

    BEGIN
        UPDATE sales.buyer_order SET status = 'STANDBY', updated_by = v_user WHERE id = v_order;
        RAISE EXCEPTION 'Expected downstream Buyer Order reopen to be rejected';
    EXCEPTION
        WHEN object_not_in_prerequisite_state THEN
            NULL;
    END;
END;
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_proc p,
             LATERAL aclexplode(COALESCE(p.proacl, acldefault('f', p.proowner))) acl
        WHERE p.oid = 'system.next_document_number(character varying, character varying, smallint)'::regprocedure
          AND acl.grantee = 0
          AND acl.privilege_type = 'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'PUBLIC must not execute the document-number allocator';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM inventory.stock_ledger_reconciliation
        WHERE NOT reconciled
    ) THEN
        RAISE EXCEPTION 'Stock ledger contains unreconciled positions';
    END IF;
END;
$$;

ROLLBACK;
