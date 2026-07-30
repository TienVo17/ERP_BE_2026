-- Pre-V013 writers of sales.buyer_order_item do not participate in advisory coordination.
-- Acquire the complete guarded-table fence with NOWAIT before validation. Any concurrent writer
-- makes the migration fail fast and roll back; operators quiesce guarded writes and rerun it.
LOCK TABLE
    sales.buyer_order_item,
    master_data.finished_good
IN SHARE ROW EXCLUSIVE MODE NOWAIT;

-- Refuse to install the guard over inconsistent business data. The migration intentionally does
-- not rewrite, archive or unlink any existing Buyer Order Item.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM master_data.finished_good fg
        WHERE fg.status = 'ARCHIVED'
          AND EXISTS (
              SELECT 1 FROM sales.buyer_order_item boi WHERE boi.finished_good_id = fg.id
          )
    ) THEN
        RAISE EXCEPTION 'MASTER_GUARD_MIGRATION_INVALID: business data references an ARCHIVED Finished Good'
            USING ERRCODE = '23514';
    END IF;
END;
$$;

-- Finished Good joins the single V012 coordination point instead of introducing a second advisory
-- key. Reusing one global transaction lock keeps the guarded write set free of multi-key cycles.
CREATE TRIGGER trg_finished_good_00_usage_coordination
BEFORE UPDATE ON master_data.finished_good
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();

CREATE TRIGGER trg_buyer_order_item_insert_00_usage_coordination
BEFORE INSERT ON sales.buyer_order_item
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();
CREATE TRIGGER trg_buyer_order_item_update_00_usage_coordination
BEFORE UPDATE ON sales.buyer_order_item
FOR EACH STATEMENT EXECUTE FUNCTION master_data.coordinate_guarded_master_usage();

-- Immediate foreign keys acquire referenced-row KEY SHARE after advisory coordination.
-- AFTER STATEMENT checks only read status and introduce no conflicting lock order.
CREATE FUNCTION master_data.require_active_finished_good_references()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM new_rows n
        LEFT JOIN master_data.finished_good fg ON fg.id = n.finished_good_id
        WHERE n.finished_good_id IS NOT NULL
          AND (fg.id IS NULL OR fg.status <> 'ACTIVE')
    ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: referenced Finished Good must be ACTIVE'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_buyer_order_item_insert_active_finished_good
AFTER INSERT ON sales.buyer_order_item
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_finished_good_references();
CREATE TRIGGER trg_buyer_order_item_update_active_finished_good
AFTER UPDATE ON sales.buyer_order_item
REFERENCING NEW TABLE AS new_rows
FOR EACH STATEMENT EXECUTE FUNCTION master_data.require_active_finished_good_references();

-- image_asset_id is intentionally outside the frozen set. Buyer Order Item snapshots carry kind,
-- style, name, uom and price, so only those plus status can invalidate a referencing document.
CREATE FUNCTION master_data.validate_finished_good_usage_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF (NEW.product_kind IS DISTINCT FROM OLD.product_kind
        OR NEW.style_no IS DISTINCT FROM OLD.style_no
        OR NEW.name IS DISTINCT FROM OLD.name
        OR NEW.size IS DISTINCT FROM OLD.size
        OR NEW.color IS DISTINCT FROM OLD.color
        OR NEW.uom_id IS DISTINCT FROM OLD.uom_id
        OR NEW.reference_price IS DISTINCT FROM OLD.reference_price
        OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
        OR NEW.status IS DISTINCT FROM OLD.status)
       AND EXISTS (
           SELECT 1 FROM sales.buyer_order_item WHERE finished_good_id = NEW.id
       ) THEN
        RAISE EXCEPTION 'MASTER_IN_USE: Finished Good is referenced by business data'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_finished_good_usage_transition
BEFORE UPDATE ON master_data.finished_good
FOR EACH ROW EXECUTE FUNCTION master_data.validate_finished_good_usage_transition();

-- Trigger execution privileges are checked when the trigger is created, so the runtime role
-- activates these guards without needing direct EXECUTE on the helper functions.
REVOKE ALL ON FUNCTION master_data.require_active_finished_good_references() FROM PUBLIC;
REVOKE ALL ON FUNCTION master_data.validate_finished_good_usage_transition() FROM PUBLIC;
