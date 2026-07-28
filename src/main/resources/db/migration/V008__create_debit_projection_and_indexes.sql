CREATE VIEW delivery.debit_note_projection AS
SELECT
    dn.delivery_no || '/' || lpad(dni.line_no::text, 2, '0') AS debit_reference,
    dn.id AS delivery_note_id,
    dn.delivery_no,
    dn.delivery_date,
    dn.customer_id,
    dn.customer_name_snapshot,
    dni.line_no,
    dni.sys_po_no_snapshot,
    dni.buyer_po_snapshot,
    dni.product_kind_snapshot,
    dni.style_no_snapshot,
    dni.name_snapshot,
    dni.size_snapshot,
    dni.color_snapshot,
    dni.uom_code_snapshot,
    dni.delivery_qty AS total_qty,
    dni.unit_price,
    dni.amount,
    dni.currency_code
FROM delivery.delivery_note dn
JOIN delivery.delivery_note_item dni ON dni.delivery_note_id = dn.id
WHERE dn.status = 'POSTED';

CREATE VIEW inventory.stock_ledger_reconciliation AS
SELECT
    sp.id AS stock_position_id,
    sp.production_order_id,
    sp.current_qty AS position_current_qty,
    COALESCE(sum(sm.quantity_signed), 0)::numeric(18,4) AS ledger_current_qty,
    sp.produced_qty AS position_produced_qty,
    COALESCE(sum(sm.quantity_signed) FILTER (WHERE sm.movement_type = 'PRODUCTION'), 0)::numeric(18,4)
        AS ledger_produced_qty,
    sp.delivered_qty AS position_delivered_qty,
    COALESCE(-sum(sm.quantity_signed) FILTER (WHERE sm.movement_type IN ('DELIVERY', 'DELIVERY_REVERSAL')), 0)::numeric(18,4)
        AS ledger_delivered_qty,
    sp.returned_qty AS position_returned_qty,
    COALESCE(sum(sm.quantity_signed) FILTER (WHERE sm.movement_type = 'RETURN'), 0)::numeric(18,4)
        AS ledger_returned_qty,
    sp.disposed_qty AS position_disposed_qty,
    COALESCE(-sum(sm.quantity_signed) FILTER (WHERE sm.movement_type = 'DISPOSE'), 0)::numeric(18,4)
        AS ledger_disposed_qty,
    (
        sp.current_qty = COALESCE(sum(sm.quantity_signed), 0)
        AND sp.produced_qty = COALESCE(sum(sm.quantity_signed) FILTER (WHERE sm.movement_type = 'PRODUCTION'), 0)
        AND sp.delivered_qty = COALESCE(-sum(sm.quantity_signed) FILTER (
            WHERE sm.movement_type IN ('DELIVERY', 'DELIVERY_REVERSAL')
        ), 0)
        AND sp.returned_qty = COALESCE(sum(sm.quantity_signed) FILTER (WHERE sm.movement_type = 'RETURN'), 0)
        AND sp.disposed_qty = COALESCE(-sum(sm.quantity_signed) FILTER (WHERE sm.movement_type = 'DISPOSE'), 0)
    ) AS reconciled
FROM inventory.stock_position sp
LEFT JOIN inventory.stock_movement sm ON sm.stock_position_id = sp.id
GROUP BY sp.id;

CREATE INDEX ix_delivery_note_item_order_item
    ON delivery.delivery_note_item (buyer_order_item_id);
CREATE INDEX ix_delivery_note_item_delivery_movement
    ON delivery.delivery_note_item (delivery_movement_id)
    WHERE delivery_movement_id IS NOT NULL;
CREATE INDEX ix_delivery_note_item_reversal_movement
    ON delivery.delivery_note_item (reversal_movement_id)
    WHERE reversal_movement_id IS NOT NULL;
CREATE INDEX ix_delivery_note_replacement
    ON delivery.delivery_note (replaces_delivery_id)
    WHERE replaces_delivery_id IS NOT NULL;
CREATE INDEX ix_stock_movement_business_date
    ON inventory.stock_movement (business_date, movement_type);
CREATE INDEX ix_production_order_source_composite
    ON production.production_order (buyer_order_item_id, buyer_order_id);
