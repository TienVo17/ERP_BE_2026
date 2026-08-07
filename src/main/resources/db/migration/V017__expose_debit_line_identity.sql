-- The frozen contract identifies a Debit row by its Delivery line, and the sort allowlist orders by
-- it, but the V008 projection never selected that column. Republish the view with the identity it
-- always described; the projection stays a live read-only view over POSTED lines.
-- CREATE OR REPLACE VIEW can only append columns, so the new identity goes last.
CREATE OR REPLACE VIEW delivery.debit_note_projection AS
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
    dni.currency_code,
    dni.id AS delivery_note_item_id
FROM delivery.delivery_note dn
JOIN delivery.delivery_note_item dni ON dni.delivery_note_id = dn.id
WHERE dn.status = 'POSTED';
