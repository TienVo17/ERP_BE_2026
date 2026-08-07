package com.company.erp.delivery.infrastructure;

import com.company.erp.delivery.api.DeliveryModels.DeliveryEventResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteItemResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliverySourcePageResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliverySourceResponse;
import com.company.erp.delivery.api.DeliveryModels.PageMetadata;
import com.company.erp.identity.application.AdminQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeliveryJdbcRepository {

    private static final Map<String, String> SOURCE_SORT = Map.of(
            "sysPoNo", "bo.sys_po_no", "availableQty", "sp.current_qty", "stockPositionId", "sp.id");
    private static final Map<String, String> NOTE_SORT = Map.of(
            "deliveryNo", "dn.delivery_no", "deliveryDate", "dn.delivery_date",
            "status", "dn.status", "id", "dn.id");
    private final JdbcClient jdbc;

    public DeliveryJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Delivery writes touch guarded Stock relationships, so they join the global coordination. */
    public void coordinate() {
        jdbc.sql("SELECT pg_catalog.pg_advisory_xact_lock(20260730, 1)")
                .query((result, row) -> Boolean.TRUE)
                .single();
    }

    public List<String> sourceSort(List<String> sort) {
        return AdminQuery.sort(sort, SOURCE_SORT, List.of("sysPoNo,asc", "stockPositionId,asc"));
    }

    public List<String> noteSort(List<String> sort) {
        return AdminQuery.sort(sort, NOTE_SORT, List.of("deliveryDate,desc", "id,asc"));
    }

    public DeliverySourcePageResponse sources(
            int page, int size, UUID customerId, String sysPoNo, String buyerPo, Boolean inStock,
            List<String> sort) {
        String from = """
                FROM inventory.stock_position sp
                JOIN sales.buyer_order_item boi ON boi.id = sp.buyer_order_item_id
                JOIN sales.buyer_order bo ON bo.id = boi.buyer_order_id
                JOIN master_data.uom u ON u.id = sp.uom_id
                WHERE (CAST(:customerId AS uuid) IS NULL OR sp.customer_id = :customerId)
                  AND (CAST(:sysPoNo AS varchar) IS NULL OR upper(bo.sys_po_no) LIKE upper(:sysPoNo))
                  AND (CAST(:buyerPo AS varchar) IS NULL OR upper(bo.buyer_po) LIKE upper(:buyerPo))
                  AND (CAST(:inStock AS boolean) IS NULL
                       OR (:inStock = true AND sp.current_qty > 0)
                       OR (:inStock = false AND sp.current_qty = 0))
                """;
        long total = sourceStatement("SELECT count(*) " + from, customerId, sysPoNo, buyerPo, inStock)
                .query(Long.class).single();
        List<DeliverySourceResponse> items = sourceStatement("""
                        SELECT sp.id, sp.buyer_order_item_id, sp.production_order_id, sp.customer_id,
                               sp.currency_code, sp.current_qty, u.code AS uom_code,
                               bo.id AS buyer_order_id, bo.sys_po_no, bo.buyer_po,
                               boi.product_kind_snapshot, boi.style_no_snapshot, boi.name_snapshot,
                               boi.size_snapshot, boi.color_snapshot
                        """ + from + " ORDER BY " + AdminQuery.orderBy(sort, SOURCE_SORT)
                        + " LIMIT :limit OFFSET :offset", customerId, sysPoNo, buyerPo, inStock)
                .param("limit", size).param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> new DeliverySourceResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("buyer_order_id", UUID.class),
                        rs.getObject("buyer_order_item_id", UUID.class),
                        rs.getObject("production_order_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("currency_code"),
                        quantity(rs.getBigDecimal("current_qty")),
                        rs.getString("uom_code"),
                        rs.getString("sys_po_no"),
                        rs.getString("buyer_po"),
                        rs.getString("product_kind_snapshot"),
                        rs.getString("style_no_snapshot"),
                        rs.getString("name_snapshot"),
                        rs.getString("size_snapshot"),
                        rs.getString("color_snapshot")))
                .list();
        return new DeliverySourcePageResponse(items,
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters("customerId", customerId, "sysPoNo", sysPoNo,
                        "buyerPo", buyerPo, "inStock", inStock), sort);
    }

    /** Locks the addressed positions in ascending UUID order, the order every writer shares. */
    public List<SourceRow> lockSources(List<UUID> positionIds) {
        return jdbc.sql("""
                        SELECT sp.id, sp.customer_id, sp.currency_code, sp.current_qty,
                               sp.delivered_qty, sp.returned_qty, sp.disposed_qty, sp.produced_qty,
                               sp.order_qty, sp.order_balance_qty, sp.version, sp.buyer_order_item_id,
                               sp.production_order_id, u.code AS uom_code,
                               bo.id AS buyer_order_id, bo.sys_po_no, bo.buyer_po,
                               bo.pic_name_snapshot, bo.po_date, bo.delivery_date,
                               bo.customer_name_snapshot, c.address AS customer_address,
                               boi.product_kind_snapshot, boi.style_no_snapshot, boi.name_snapshot,
                               boi.size_snapshot, boi.color_snapshot
                        FROM inventory.stock_position sp
                        JOIN sales.buyer_order_item boi ON boi.id = sp.buyer_order_item_id
                        JOIN sales.buyer_order bo ON bo.id = boi.buyer_order_id
                        JOIN master_data.customer c ON c.id = sp.customer_id
                        JOIN master_data.uom u ON u.id = sp.uom_id
                        WHERE sp.id IN (:ids)
                        ORDER BY sp.id
                        FOR UPDATE OF sp
                        """)
                .param("ids", positionIds)
                .query((rs, row) -> new SourceRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("customer_name_snapshot"),
                        rs.getString("customer_address"),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("current_qty"),
                        rs.getBigDecimal("delivered_qty"),
                        rs.getBigDecimal("returned_qty"),
                        rs.getBigDecimal("disposed_qty"),
                        rs.getBigDecimal("produced_qty"),
                        rs.getBigDecimal("order_qty"),
                        rs.getLong("version"),
                        rs.getObject("buyer_order_id", UUID.class),
                        rs.getObject("buyer_order_item_id", UUID.class),
                        rs.getObject("production_order_id", UUID.class),
                        rs.getString("sys_po_no"),
                        rs.getString("buyer_po"),
                        rs.getString("pic_name_snapshot"),
                        rs.getDate("po_date").toLocalDate(),
                        rs.getDate("delivery_date").toLocalDate(),
                        rs.getString("product_kind_snapshot"),
                        rs.getString("style_no_snapshot"),
                        rs.getString("name_snapshot"),
                        rs.getString("size_snapshot"),
                        rs.getString("color_snapshot"),
                        rs.getString("uom_code")))
                .list();
    }

    public UUID insertDraft(
            UUID customerId, String customerName, String customerAddress, LocalDate deliveryDate,
            String currencyCode, BigDecimal vatPercent, String remark, BigDecimal totalQty,
            BigDecimal totalAmount, UUID replacesDeliveryId, UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO delivery.delivery_note (
                            id, customer_id, customer_name_snapshot, customer_address_snapshot,
                            delivery_date, currency_code, vat_percent, remark, total_qty, total_amount,
                            replaces_delivery_id, created_by, updated_by
                        ) VALUES (
                            :id, :customerId, :customerName, :customerAddress,
                            :deliveryDate, :currencyCode, :vatPercent, :remark, :totalQty, :totalAmount,
                            :replacesDeliveryId, :actor, :actor
                        )
                        """)
                .param("id", id)
                .param("customerId", customerId)
                .param("customerName", customerName)
                .param("customerAddress", customerAddress, java.sql.Types.VARCHAR)
                .param("deliveryDate", deliveryDate)
                .param("currencyCode", currencyCode)
                .param("vatPercent", vatPercent)
                .param("remark", remark, java.sql.Types.VARCHAR)
                .param("totalQty", totalQty)
                .param("totalAmount", totalAmount)
                .param("replacesDeliveryId", replacesDeliveryId, java.sql.Types.OTHER)
                .param("actor", actor)
                .update();
        return id;
    }

    public void insertItem(UUID deliveryId, int lineNo, SourceRow source, BigDecimal quantity,
            BigDecimal unitPrice, BigDecimal amount) {
        jdbc.sql("""
                        INSERT INTO delivery.delivery_note_item (
                            id, delivery_note_id, line_no, stock_position_id, buyer_order_id,
                            buyer_order_item_id, production_order_id, sys_po_no_snapshot,
                            buyer_po_snapshot, pic_name_snapshot, po_date_snapshot,
                            promised_delivery_date_snapshot, product_kind_snapshot, style_no_snapshot,
                            name_snapshot, size_snapshot, color_snapshot, uom_code_snapshot,
                            currency_code, order_qty_snapshot, produced_qty_snapshot, delivery_qty,
                            unit_price, amount
                        ) VALUES (
                            :id, :deliveryId, :lineNo, :positionId, :buyerOrderId,
                            :buyerOrderItemId, :productionOrderId, :sysPoNo,
                            :buyerPo, :picName, :poDate,
                            :promisedDate, :productKind, :styleNo,
                            :name, :size, :color, :uomCode,
                            :currencyCode, :orderQty, :producedQty, :deliveryQty,
                            :unitPrice, :amount
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("deliveryId", deliveryId)
                .param("lineNo", lineNo)
                .param("positionId", source.id())
                .param("buyerOrderId", source.buyerOrderId())
                .param("buyerOrderItemId", source.buyerOrderItemId())
                .param("productionOrderId", source.productionOrderId())
                .param("sysPoNo", source.sysPoNo())
                .param("buyerPo", source.buyerPo())
                .param("picName", source.picName())
                .param("poDate", source.poDate())
                .param("promisedDate", source.promisedDeliveryDate())
                .param("productKind", source.productKind())
                .param("styleNo", source.styleNo())
                .param("name", source.name())
                .param("size", source.size(), java.sql.Types.VARCHAR)
                .param("color", source.color(), java.sql.Types.VARCHAR)
                .param("uomCode", source.uomCode())
                .param("currencyCode", source.currencyCode())
                .param("orderQty", source.orderQty())
                .param("producedQty", source.producedQty())
                .param("deliveryQty", quantity)
                .param("unitPrice", unitPrice)
                .param("amount", amount)
                .update();
    }

    public void deleteItems(UUID deliveryId) {
        jdbc.sql("DELETE FROM delivery.delivery_note_item WHERE delivery_note_id = :id")
                .param("id", deliveryId).update();
    }

    public int updateDraft(UUID id, long version, LocalDate deliveryDate, BigDecimal vatPercent,
            String remark, BigDecimal totalQty, BigDecimal totalAmount, UUID actor) {
        return jdbc.sql("""
                        UPDATE delivery.delivery_note
                        SET delivery_date = :deliveryDate, vat_percent = :vatPercent, remark = :remark,
                            total_qty = :totalQty, total_amount = :totalAmount,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'DRAFT'
                        """)
                .param("deliveryDate", deliveryDate)
                .param("vatPercent", vatPercent)
                .param("remark", remark, java.sql.Types.VARCHAR)
                .param("totalQty", totalQty)
                .param("totalAmount", totalAmount)
                .param("actor", actor)
                .param("id", id)
                .param("version", version)
                .update();
    }

    public Optional<HeaderRow> lock(UUID id) {
        return jdbc.sql("SELECT id, version, status, customer_id, currency_code FROM delivery.delivery_note WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, row) -> new HeaderRow(
                        rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status"),
                        rs.getObject("customer_id", UUID.class), rs.getString("currency_code")))
                .optional();
    }

    public void event(UUID deliveryId, String type, UUID actor, String reason) {
        jdbc.sql("""
                        INSERT INTO delivery.delivery_event (
                            id, delivery_note_id, event_type, actor_user_id, reason
                        ) VALUES (:id, :deliveryId, :type, :actor, :reason)
                        """)
                .param("id", UUID.randomUUID())
                .param("deliveryId", deliveryId)
                .param("type", type)
                .param("actor", actor)
                .param("reason", reason, java.sql.Types.VARCHAR)
                .update();
    }

    /** The active rate for the delivery month; posting without one is a business conflict. */
    public Optional<RateRow> activeRateForMonth(LocalDate deliveryDate) {
        return jdbc.sql("""
                        SELECT id, vnd_usd_rate, won_usd_rate
                        FROM master_data.monthly_exchange_rate
                        WHERE effective_month = date_trunc('month', CAST(:deliveryDate AS date))::date
                          AND status = 'ACTIVE'
                        """)
                .param("deliveryDate", deliveryDate)
                .query((rs, row) -> new RateRow(
                        rs.getObject("id", UUID.class),
                        rs.getBigDecimal("vnd_usd_rate"),
                        rs.getBigDecimal("won_usd_rate")))
                .optional();
    }

    public List<ItemRow> itemsForPost(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT id, line_no, stock_position_id, delivery_qty
                        FROM delivery.delivery_note_item
                        WHERE delivery_note_id = :id
                        ORDER BY stock_position_id
                        """)
                .param("id", deliveryId)
                .query((rs, row) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("stock_position_id", UUID.class),
                        rs.getBigDecimal("delivery_qty")))
                .list();
    }

    /** Appends the DELIVERY movement and links it to its line, keyed by the server command id. */
    public UUID consumeStock(SourceRow position, ItemRow item, UUID deliveryId, LocalDate businessDate,
            UUID commandId, UUID actor) {
        BigDecimal balance = position.currentQty().subtract(item.deliveryQty());
        UUID movementId = UUID.randomUUID();
        jdbc.sql("""
                        UPDATE inventory.stock_position
                        SET delivered_qty = delivered_qty + :quantity,
                            current_qty = current_qty - :quantity,
                            order_balance_qty = order_qty - (delivered_qty + :quantity) + returned_qty,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version
                        """)
                .param("quantity", item.deliveryQty())
                .param("actor", actor)
                .param("id", position.id())
                .param("version", position.version())
                .update();
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (
                            id, stock_position_id, movement_type, quantity_signed, balance_after,
                            business_date, source_type, source_id, source_item_id, idempotency_key,
                            created_by
                        ) VALUES (
                            :id, :positionId, 'DELIVERY', :signed, :balance,
                            :businessDate, 'DELIVERY', :deliveryId, :itemId, :commandId,
                            :actor
                        )
                        """)
                .param("id", movementId)
                .param("positionId", position.id())
                .param("signed", item.deliveryQty().negate())
                .param("balance", balance)
                .param("businessDate", businessDate)
                .param("deliveryId", deliveryId)
                .param("itemId", item.id())
                .param("commandId", commandId + ":" + item.id())
                .param("actor", actor)
                .update();
        jdbc.sql("UPDATE delivery.delivery_note_item SET delivery_movement_id = :movementId WHERE id = :id")
                .param("movementId", movementId)
                .param("id", item.id())
                .update();
        return movementId;
    }

    /** Any RETURN against this delivery's lines blocks reversal: the goods already came back. */
    public boolean hasReturns(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1
                            FROM delivery.delivery_note_item dni
                            JOIN inventory.stock_movement sm
                                ON sm.return_source_delivery_item_id = dni.id
                            WHERE dni.delivery_note_id = :id AND sm.movement_type = 'RETURN'
                        )
                        """)
                .param("id", deliveryId)
                .query(Boolean.class)
                .single();
    }

    public List<ItemRow> itemsForReversal(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT id, line_no, stock_position_id, delivery_qty, unit_price
                        FROM delivery.delivery_note_item
                        WHERE delivery_note_id = :id
                        ORDER BY stock_position_id
                        """)
                .param("id", deliveryId)
                .query((rs, row) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("stock_position_id", UUID.class),
                        rs.getBigDecimal("delivery_qty")))
                .list();
    }

    public List<ReplacementLine> replacementLines(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT stock_position_id, delivery_qty, unit_price
                        FROM delivery.delivery_note_item
                        WHERE delivery_note_id = :id
                        ORDER BY line_no
                        """)
                .param("id", deliveryId)
                .query((rs, row) -> new ReplacementLine(
                        rs.getObject("stock_position_id", UUID.class),
                        rs.getBigDecimal("delivery_qty"),
                        rs.getBigDecimal("unit_price")))
                .list();
    }

    /** Restores the position and appends the reversal movement; the DELIVERY row is never edited. */
    public void restoreStock(SourceRow position, ItemRow item, UUID deliveryId, LocalDate businessDate,
            UUID commandId, String reason, UUID actor) {
        BigDecimal balance = position.currentQty().add(item.deliveryQty());
        UUID movementId = UUID.randomUUID();
        jdbc.sql("""
                        UPDATE inventory.stock_position
                        SET delivered_qty = delivered_qty - :quantity,
                            current_qty = current_qty + :quantity,
                            order_balance_qty = order_qty - (delivered_qty - :quantity) + returned_qty,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version
                        """)
                .param("quantity", item.deliveryQty())
                .param("actor", actor)
                .param("id", position.id())
                .param("version", position.version())
                .update();
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (
                            id, stock_position_id, movement_type, quantity_signed, balance_after,
                            business_date, source_type, source_id, source_item_id, idempotency_key,
                            reason, created_by
                        ) VALUES (
                            :id, :positionId, 'DELIVERY_REVERSAL', :signed, :balance,
                            :businessDate, 'DELIVERY', :deliveryId, :itemId, :commandId,
                            :reason, :actor
                        )
                        """)
                .param("id", movementId)
                .param("positionId", position.id())
                .param("signed", item.deliveryQty())
                .param("balance", balance)
                .param("businessDate", businessDate)
                .param("deliveryId", deliveryId)
                .param("itemId", item.id())
                .param("commandId", commandId + ":" + item.id())
                .param("reason", reason)
                .param("actor", actor)
                .update();
        jdbc.sql("UPDATE delivery.delivery_note_item SET reversal_movement_id = :movementId WHERE id = :id")
                .param("movementId", movementId)
                .param("id", item.id())
                .update();
    }

    public int markReversed(UUID id, long version, String reason, UUID actor) {
        return jdbc.sql("""
                        UPDATE delivery.delivery_note
                        SET status = 'REVERSED', reversed_at = clock_timestamp(), reversed_by = :actor,
                            reversal_reason = :reason, version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'POSTED'
                        """)
                .param("reason", reason)
                .param("actor", actor)
                .param("id", id)
                .param("version", version)
                .update();
    }

    public int markPosted(UUID id, long version, String deliveryNo, RateRow rate, UUID actor) {
        return jdbc.sql("""
                        UPDATE delivery.delivery_note
                        SET status = 'POSTED', delivery_no = :deliveryNo, exchange_rate_id = :rateId,
                            vnd_usd_rate_snapshot = :vndRate, won_usd_rate_snapshot = :wonRate,
                            posted_at = clock_timestamp(), posted_by = :actor,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'DRAFT'
                        """)
                .param("deliveryNo", deliveryNo)
                .param("rateId", rate.id())
                .param("vndRate", rate.vndUsdRate())
                .param("wonRate", rate.wonUsdRate())
                .param("actor", actor)
                .param("id", id)
                .param("version", version)
                .update();
    }

    public Optional<DeliveryNoteResponse> find(UUID id) {
        Optional<DeliveryNoteResponse> header = jdbc.sql("""
                        SELECT dn.*, successor.id AS replacement_delivery_id
                        FROM delivery.delivery_note dn
                        LEFT JOIN delivery.delivery_note successor ON successor.replaces_delivery_id = dn.id
                        WHERE dn.id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> header(rs))
                .optional();
        return header.map(value -> new DeliveryNoteResponse(
                value.id(), value.version(), value.deliveryNo(), value.customerId(), value.customerName(),
                value.customerAddress(), value.deliveryDate(), value.currencyCode(), value.vndUsdRate(),
                value.wonUsdRate(), value.vatPercent(), value.totalQty(), value.totalAmount(),
                value.status(), value.replacesDeliveryId(), value.replacementDeliveryId(), value.remark(),
                items(id), events(id), value.createdAt(), value.updatedAt()));
    }

    private List<DeliveryNoteItemResponse> items(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT * FROM delivery.delivery_note_item
                        WHERE delivery_note_id = :id
                        ORDER BY line_no
                        """)
                .param("id", deliveryId)
                .query((rs, row) -> new DeliveryNoteItemResponse(
                        rs.getObject("id", UUID.class),
                        rs.getInt("line_no"),
                        rs.getObject("stock_position_id", UUID.class),
                        rs.getObject("buyer_order_id", UUID.class),
                        rs.getObject("buyer_order_item_id", UUID.class),
                        rs.getObject("production_order_id", UUID.class),
                        rs.getString("sys_po_no_snapshot"),
                        rs.getString("buyer_po_snapshot"),
                        rs.getString("product_kind_snapshot"),
                        rs.getString("style_no_snapshot"),
                        rs.getString("name_snapshot"),
                        rs.getString("size_snapshot"),
                        rs.getString("color_snapshot"),
                        rs.getString("uom_code_snapshot"),
                        rs.getString("currency_code"),
                        quantity(rs.getBigDecimal("delivery_qty")),
                        price(rs.getBigDecimal("unit_price")),
                        amount(rs.getBigDecimal("amount"))))
                .list();
    }

    private List<DeliveryEventResponse> events(UUID deliveryId) {
        return jdbc.sql("""
                        SELECT id, event_type, actor_user_id, reason, occurred_at
                        FROM delivery.delivery_event
                        WHERE delivery_note_id = :id
                        ORDER BY occurred_at, id
                        """)
                .param("id", deliveryId)
                .query((rs, row) -> new DeliveryEventResponse(
                        rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("reason"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public void forceDeferredConstraints() {
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        jdbc.sql("SET CONSTRAINTS ALL DEFERRED").update();
    }

    private JdbcClient.StatementSpec sourceStatement(
            String sql, UUID customerId, String sysPoNo, String buyerPo, Boolean inStock) {
        return jdbc.sql(sql)
                .param("customerId", customerId)
                .param("sysPoNo", sysPoNo == null ? null : "%" + sysPoNo.trim() + "%")
                .param("buyerPo", buyerPo == null ? null : "%" + buyerPo.trim() + "%")
                .param("inStock", inStock);
    }

    private static DeliveryNoteResponse header(ResultSet rs) throws SQLException {
        return new DeliveryNoteResponse(
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                rs.getString("delivery_no"),
                rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name_snapshot"),
                rs.getString("customer_address_snapshot"),
                rs.getDate("delivery_date").toLocalDate(),
                rs.getString("currency_code"),
                nullableRate(rs.getBigDecimal("vnd_usd_rate_snapshot")),
                nullableRate(rs.getBigDecimal("won_usd_rate_snapshot")),
                percent(rs.getBigDecimal("vat_percent")),
                quantity(rs.getBigDecimal("total_qty")),
                amount(rs.getBigDecimal("total_amount")),
                rs.getString("status"),
                rs.getObject("replaces_delivery_id", UUID.class),
                rs.getObject("replacement_delivery_id", UUID.class),
                rs.getString("remark"),
                List.of(),
                List.of(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static String quantity(BigDecimal value) { return value.setScale(4).toPlainString(); }
    private static String price(BigDecimal value) { return value.setScale(6).toPlainString(); }
    private static String amount(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private static String percent(BigDecimal value) { return value.setScale(4).toPlainString(); }
    private static String nullableRate(BigDecimal value) {
        return value == null ? null : value.setScale(6).toPlainString();
    }

    public record HeaderRow(UUID id, long version, String status, UUID customerId, String currencyCode) { }

    public record RateRow(UUID id, BigDecimal vndUsdRate, BigDecimal wonUsdRate) { }

    public record ItemRow(UUID id, int lineNo, UUID stockPositionId, BigDecimal deliveryQty) { }

    public record ReplacementLine(UUID stockPositionId, BigDecimal deliveryQty, BigDecimal unitPrice) { }

    public record SourceRow(
            UUID id, UUID customerId, String customerName, String customerAddress, String currencyCode,
            BigDecimal currentQty, BigDecimal deliveredQty, BigDecimal returnedQty, BigDecimal disposedQty,
            BigDecimal producedQty, BigDecimal orderQty, long version, UUID buyerOrderId,
            UUID buyerOrderItemId, UUID productionOrderId, String sysPoNo, String buyerPo, String picName,
            LocalDate poDate, LocalDate promisedDeliveryDate, String productKind, String styleNo,
            String name, String size, String color, String uomCode) { }
}
