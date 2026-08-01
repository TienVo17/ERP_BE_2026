package com.company.erp.sales.infrastructure;

import com.company.erp.identity.application.AdminQuery;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCreateRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderItemRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderItemResponse;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderResponse;
import com.company.erp.sales.api.BuyerOrderModels.CustomBuyerOrderItemRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BuyerOrderJdbcRepository {

    private static final Map<String, String> SORT = Map.of(
            "sysPoNo", "bo.sys_po_no", "poDate", "bo.po_date",
            "deliveryDate", "bo.delivery_date", "status", "bo.status", "id", "bo.id");
    private static final String HEADER_SELECT = """
            SELECT bo.*
            FROM sales.buyer_order bo
            """;
    private static final String FILTER = """
            WHERE (CAST(:status AS varchar) IS NULL OR bo.status = :status)
              AND (CAST(:customerId AS uuid) IS NULL OR bo.customer_id = :customerId)
              AND (CAST(:sysPoNo AS varchar) IS NULL OR upper(bo.sys_po_no) LIKE upper(:sysPoNo))
              AND (CAST(:buyerPo AS varchar) IS NULL OR lower(bo.buyer_po) LIKE lower(:buyerPo))
              AND (CAST(:poDateFrom AS date) IS NULL OR bo.po_date >= :poDateFrom)
              AND (CAST(:poDateTo AS date) IS NULL OR bo.po_date <= :poDateTo)
            """;

    private final JdbcClient jdbc;

    public BuyerOrderJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> sort(List<String> requested) {
        return AdminQuery.sort(requested, SORT, List.of("poDate,desc", "id,asc"));
    }

    /** Reuses the V012/V013 global coordination point before any guarded row lock. */
    public void coordinateGuardedReferences() {
        jdbc.sql("SELECT pg_catalog.pg_advisory_xact_lock(20260730, 1)")
                .query((result, row) -> Boolean.TRUE)
                .single();
    }

    public List<BuyerOrderResponse> list(
            int page, int size, String status, UUID customerId, String sysPoNo, String buyerPo,
            LocalDate poDateFrom, LocalDate poDateTo, List<String> sort) {
        List<BuyerOrderResponse> headers = filtered(jdbc.sql(HEADER_SELECT + FILTER
                        + "ORDER BY " + AdminQuery.orderBy(sort, SORT) + " LIMIT :limit OFFSET :offset"),
                status, customerId, sysPoNo, buyerPo, poDateFrom, poDateTo)
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> mapHeader(rs, List.of()))
                .list();
        if (headers.isEmpty()) {
            return headers;
        }
        List<UUID> ids = headers.stream().map(BuyerOrderResponse::id).toList();
        Map<UUID, List<BuyerOrderItemResponse>> items = itemsByOrders(ids);
        return headers.stream().map(header -> withItems(header, items.getOrDefault(header.id(), List.of()))).toList();
    }

    public long count(
            String status, UUID customerId, String sysPoNo, String buyerPo,
            LocalDate poDateFrom, LocalDate poDateTo) {
        return filtered(jdbc.sql("SELECT count(*) FROM sales.buyer_order bo " + FILTER),
                status, customerId, sysPoNo, buyerPo, poDateFrom, poDateTo)
                .query(Long.class).single();
    }

    public Optional<BuyerOrderResponse> find(UUID id) {
        return jdbc.sql(HEADER_SELECT + "WHERE bo.id = :id")
                .param("id", id)
                .query((rs, row) -> mapHeader(rs, items(id)))
                .optional();
    }

    public Optional<BuyerOrderResponse> findForUpdate(UUID id) {
        return jdbc.sql(HEADER_SELECT + "WHERE bo.id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, row) -> mapHeader(rs, items(id)))
                .optional();
    }

    public Optional<CustomerSnapshot> customer(UUID id) {
        return jdbc.sql("""
                        SELECT id, name, short_name, currency_code
                        FROM master_data.customer
                        WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("id", id)
                .query((rs, row) -> new CustomerSnapshot(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("short_name"), rs.getString("currency_code")))
                .optional();
    }

    public Optional<String> activeContactName(UUID contactId, UUID customerId) {
        return jdbc.sql("""
                        SELECT name FROM master_data.customer_contact
                        WHERE id = :id AND customer_id = :customerId AND status = 'ACTIVE'
                        """)
                .param("id", contactId).param("customerId", customerId)
                .query(String.class).optional();
    }

    public Optional<FinishedGoodSnapshot> finishedGood(UUID id) {
        return jdbc.sql("""
                        SELECT fg.id, fg.product_kind, fg.style_no, fg.name, fg.size, fg.color,
                               fg.uom_id, u.code AS uom_code
                        FROM master_data.finished_good fg
                        JOIN master_data.uom u ON u.id = fg.uom_id AND u.status = 'ACTIVE'
                        WHERE fg.id = :id AND fg.status = 'ACTIVE'
                        """)
                .param("id", id)
                .query((rs, row) -> new FinishedGoodSnapshot(
                        rs.getObject("id", UUID.class), rs.getString("product_kind"),
                        rs.getString("style_no"), rs.getString("name"), rs.getString("size"),
                        rs.getString("color"), rs.getObject("uom_id", UUID.class), rs.getString("uom_code")))
                .optional();
    }

    public Optional<String> activeUomCode(UUID id) {
        return jdbc.sql("SELECT code FROM master_data.uom WHERE id = :id AND status = 'ACTIVE'")
                .param("id", id).query(String.class).optional();
    }

    public boolean activeCurrency(String code) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM master_data.currency WHERE code = :code AND active)")
                .param("code", code).query(Boolean.class).single();
    }

    public UUID insertOrder(
            String sysPoNo, BuyerOrderCreateRequest request, CustomerSnapshot customer,
            String picName, UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO sales.buyer_order (
                            id, sys_po_no, order_type, customer_id, customer_name_snapshot,
                            customer_short_name_snapshot, customer_contact_id, pic_source,
                            pic_name_snapshot, buyer_po, po_date, delivery_date, created_by, updated_by
                        ) VALUES (
                            :id, :sysPoNo, :orderType, :customerId, :customerName,
                            :customerShortName, :contactId, :picSource, :picName,
                            :buyerPo, :poDate, :deliveryDate, :actor, :actor
                        )
                        """)
                .param("id", id).param("sysPoNo", sysPoNo)
                .param("orderType", request.orderType()).param("customerId", request.customerId())
                .param("customerName", customer.name()).param("customerShortName", customer.shortName())
                .param("contactId", request.customerContactId()).param("picSource", request.picSource())
                .param("picName", picName).param("buyerPo", request.buyerPo())
                .param("poDate", request.poDate()).param("deliveryDate", request.deliveryDate())
                .param("actor", actor).update();
        return id;
    }

    public void insertItems(UUID orderId, int revision, List<ResolvedItem> items, UUID actor) {
        int line = 1;
        for (ResolvedItem item : items) {
            jdbc.sql("""
                            INSERT INTO sales.buyer_order_item (
                                id, buyer_order_id, line_no, revision, active_revision, status,
                                is_custom, finished_good_id, product_kind_snapshot, style_no_snapshot,
                                name_snapshot, size_snapshot, color_snapshot, uom_id, uom_code_snapshot,
                                order_qty, use_stock_qty, production_qty, unit_price, currency_code,
                                amount, remark, created_by, updated_by
                            ) VALUES (
                                :id, :orderId, :lineNo, :revision, true, 'ACTIVE', :custom,
                                :finishedGoodId, :productKind, :styleNo, :name, :size, :color,
                                :uomId, :uomCode, :orderQty, 0, :orderQty, :unitPrice,
                                :currencyCode, :amount, :remark, :actor, :actor
                            )
                            """)
                    .param("id", UUID.randomUUID()).param("orderId", orderId).param("lineNo", line++)
                    .param("revision", revision).param("custom", item.custom())
                    .param("finishedGoodId", item.finishedGoodId()).param("productKind", item.productKind())
                    .param("styleNo", item.styleNo()).param("name", item.name())
                    .param("size", item.size()).param("color", item.color())
                    .param("uomId", item.uomId()).param("uomCode", item.uomCode())
                    .param("orderQty", item.orderQty()).param("unitPrice", item.unitPrice())
                    .param("currencyCode", item.currencyCode()).param("amount", item.amount())
                    .param("remark", item.remark()).param("actor", actor).update();
        }
    }

    public int updateHeader(
            UUID id, long version, BuyerOrderCreateRequest request, CustomerSnapshot customer,
            String picName, UUID actor) {
        return jdbc.sql("""
                        UPDATE sales.buyer_order SET
                            order_type = :orderType, customer_id = :customerId,
                            customer_name_snapshot = :customerName,
                            customer_short_name_snapshot = :customerShortName,
                            customer_contact_id = :contactId, pic_source = :picSource,
                            pic_name_snapshot = :picName, buyer_po = :buyerPo,
                            po_date = :poDate, delivery_date = :deliveryDate,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'STANDBY'
                        """)
                .param("orderType", request.orderType()).param("customerId", request.customerId())
                .param("customerName", customer.name()).param("customerShortName", customer.shortName())
                .param("contactId", request.customerContactId()).param("picSource", request.picSource())
                .param("picName", picName).param("buyerPo", request.buyerPo())
                .param("poDate", request.poDate()).param("deliveryDate", request.deliveryDate())
                .param("actor", actor).param("id", id).param("version", version).update();
    }

    public void deleteActiveItems(UUID orderId) {
        jdbc.sql("DELETE FROM sales.buyer_order_item WHERE buyer_order_id = :id AND active_revision")
                .param("id", orderId).update();
    }

    public int confirm(UUID id, long version, UUID actor) {
        return jdbc.sql("""
                        UPDATE sales.buyer_order SET status = 'CONFIRMED', version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'STANDBY'
                        """).param("actor", actor).param("id", id).param("version", version).update();
    }

    public void lockProductionOrders(UUID orderId) {
        jdbc.sql("SELECT id FROM production.production_order WHERE buyer_order_id = :id ORDER BY id FOR UPDATE")
                .param("id", orderId).query(UUID.class).list();
    }

    /** Locks every referenced master row while confirm revalidates its active state. */
    public boolean activeReferences(UUID orderId) {
        String customerStatus = jdbc.sql("""
                        SELECT c.status
                        FROM sales.buyer_order bo
                        JOIN master_data.customer c ON c.id = bo.customer_id
                        WHERE bo.id = :id
                        FOR NO KEY UPDATE OF c
                        """).param("id", orderId).query(String.class).single();
        if (!"ACTIVE".equals(customerStatus)) {
            return false;
        }

        Optional<String> contactStatus = jdbc.sql("""
                        SELECT cc.status
                        FROM sales.buyer_order bo
                        JOIN master_data.customer_contact cc
                          ON cc.id = bo.customer_contact_id AND cc.customer_id = bo.customer_id
                        WHERE bo.id = :id AND bo.pic_source = 'MASTER'
                        FOR NO KEY UPDATE OF cc
                        """).param("id", orderId).query(String.class).optional();
        String picSource = jdbc.sql("SELECT pic_source FROM sales.buyer_order WHERE id = :id")
                .param("id", orderId).query(String.class).single();
        if ("MASTER".equals(picSource) && !"ACTIVE".equals(contactStatus.orElse(null))) {
            return false;
        }

        List<ReferenceState> itemReferences = jdbc.sql("""
                        SELECT u.status AS uom_status, currency.active AS currency_active
                        FROM sales.buyer_order_item boi
                        JOIN master_data.uom u ON u.id = boi.uom_id
                        JOIN master_data.currency currency ON currency.code = boi.currency_code
                        WHERE boi.buyer_order_id = :id AND boi.active_revision
                        ORDER BY boi.id
                        FOR NO KEY UPDATE OF u, currency
                        """).param("id", orderId).query((result, row) -> new ReferenceState(
                        result.getString("uom_status"), result.getBoolean("currency_active"))).list();
        if (itemReferences.stream().anyMatch(reference ->
                !"ACTIVE".equals(reference.uomStatus()) || !reference.currencyActive())) {
            return false;
        }

        return jdbc.sql("""
                        SELECT fg.status
                        FROM sales.buyer_order_item boi
                        JOIN master_data.finished_good fg ON fg.id = boi.finished_good_id
                        WHERE boi.buyer_order_id = :id AND boi.active_revision AND NOT boi.is_custom
                        ORDER BY boi.id
                        FOR NO KEY UPDATE OF fg
                        """).param("id", orderId).query(String.class).list().stream()
                .allMatch("ACTIVE"::equals);
    }

    public boolean downstreamActivity(UUID orderId) {
        return jdbc.sql("""
                        SELECT EXISTS(
                            SELECT 1
                            FROM production.production_order po
                            LEFT JOIN inventory.stock_position sp ON sp.production_order_id = po.id
                            LEFT JOIN inventory.stock_movement sm ON sm.stock_position_id = sp.id
                            LEFT JOIN delivery.delivery_note_item dni ON dni.production_order_id = po.id
                            WHERE po.buyer_order_id = :id
                              AND (po.status = 'FINISHED' OR po.production_group_id IS NOT NULL
                                   OR sp.id IS NOT NULL OR sm.id IS NOT NULL OR dni.id IS NOT NULL)
                        )
                        """).param("id", orderId).query(Boolean.class).single();
    }

    public void cancelOpenProduction(UUID orderId, UUID actor, String reason) {
        List<UUID> ids = jdbc.sql("""
                        UPDATE production.production_order
                        SET status = 'CANCELLED', version = version + 1, updated_by = :actor
                        WHERE buyer_order_id = :id AND status = 'OPEN'
                        RETURNING id
                        """).param("actor", actor).param("id", orderId).query(UUID.class).list();
        for (UUID id : ids) {
            jdbc.sql("""
                            INSERT INTO production.production_event (
                                id, production_order_id, event_type, actor_user_id, reason
                            ) VALUES (:id, :orderId, 'CANCELLED', :actor, :reason)
                            """).param("id", UUID.randomUUID()).param("orderId", id)
                    .param("actor", actor).param("reason", reason).update();
        }
    }

    public void retireItems(UUID orderId, int revision, UUID actor) {
        jdbc.sql("""
                        UPDATE sales.buyer_order_item
                        SET active_revision = false, status = 'CANCELLED', updated_by = :actor
                        WHERE buyer_order_id = :id AND revision = :revision AND active_revision
                        """).param("actor", actor).param("id", orderId).param("revision", revision).update();
    }

    public int reopen(UUID id, long version, int newRevision, UUID actor) {
        return jdbc.sql("""
                        UPDATE sales.buyer_order
                        SET status = 'STANDBY', active_revision = :revision,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'CONFIRMED'
                        """).param("revision", newRevision).param("actor", actor)
                .param("id", id).param("version", version).update();
    }

    /** Force commit-time relationship guards before idempotency stores a completed response. */
    public void forceDeferredConstraints() {
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        jdbc.sql("SET CONSTRAINTS ALL DEFERRED").update();
    }

    private List<BuyerOrderItemResponse> items(UUID orderId) {
        return jdbc.sql("""
                        SELECT * FROM sales.buyer_order_item
                        WHERE buyer_order_id = :id
                        ORDER BY revision, line_no, id
                        """).param("id", orderId).query((rs, row) -> mapItem(rs)).list();
    }

    private Map<UUID, List<BuyerOrderItemResponse>> itemsByOrders(List<UUID> orderIds) {
        Map<UUID, List<BuyerOrderItemResponse>> grouped = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                        SELECT * FROM sales.buyer_order_item
                        WHERE buyer_order_id IN (:ids)
                        ORDER BY buyer_order_id, revision, line_no, id
                        """).param("ids", orderIds).query((rs, row) -> new ItemWithOrder(
                        rs.getObject("buyer_order_id", UUID.class), mapItem(rs)))
                .list().forEach(value -> grouped.computeIfAbsent(value.orderId(), ignored -> new ArrayList<>())
                        .add(value.item()));
        return grouped;
    }

    private static JdbcClient.StatementSpec filtered(
            JdbcClient.StatementSpec statement, String status, UUID customerId, String sysPoNo,
            String buyerPo, LocalDate poDateFrom, LocalDate poDateTo) {
        return statement.param("status", status).param("customerId", customerId)
                .param("sysPoNo", sysPoNo == null ? null : "%" + sysPoNo.trim() + "%")
                .param("buyerPo", buyerPo == null ? null : "%" + buyerPo.trim() + "%")
                .param("poDateFrom", poDateFrom).param("poDateTo", poDateTo);
    }

    private static BuyerOrderResponse mapHeader(ResultSet rs, List<BuyerOrderItemResponse> items) throws SQLException {
        return new BuyerOrderResponse(
                rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("sys_po_no"),
                rs.getInt("active_revision"), rs.getString("status"), rs.getString("order_type"),
                rs.getObject("customer_id", UUID.class), rs.getString("customer_name_snapshot"),
                rs.getString("customer_short_name_snapshot"), rs.getObject("customer_contact_id", UUID.class),
                rs.getString("pic_source"), rs.getString("pic_name_snapshot"), rs.getString("buyer_po"),
                rs.getObject("po_date", LocalDate.class), rs.getObject("delivery_date", LocalDate.class), items,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static BuyerOrderItemResponse mapItem(ResultSet rs) throws SQLException {
        return new BuyerOrderItemResponse(
                rs.getObject("id", UUID.class), rs.getInt("line_no"), rs.getInt("revision"),
                rs.getBoolean("active_revision"), rs.getString("status"), rs.getBoolean("is_custom"),
                rs.getObject("finished_good_id", UUID.class), rs.getString("product_kind_snapshot"),
                rs.getString("style_no_snapshot"), rs.getString("name_snapshot"),
                rs.getString("size_snapshot"), rs.getString("color_snapshot"),
                rs.getObject("uom_id", UUID.class), rs.getString("uom_code_snapshot"),
                quantity(rs.getBigDecimal("order_qty")), quantity(rs.getBigDecimal("use_stock_qty")),
                quantity(rs.getBigDecimal("production_qty")), price(rs.getBigDecimal("unit_price")),
                rs.getString("currency_code"), amount(rs.getBigDecimal("amount")), rs.getString("remark"));
    }

    private static BuyerOrderResponse withItems(BuyerOrderResponse source, List<BuyerOrderItemResponse> items) {
        return new BuyerOrderResponse(source.id(), source.version(), source.sysPoNo(), source.revision(),
                source.status(), source.orderType(), source.customerId(), source.customerName(),
                source.customerShortName(), source.customerContactId(), source.picSource(), source.picName(),
                source.buyerPo(), source.poDate(), source.deliveryDate(), items, source.createdAt(), source.updatedAt());
    }

    private static String quantity(BigDecimal value) { return value.setScale(4).toPlainString(); }
    private static String price(BigDecimal value) { return value.setScale(6).toPlainString(); }
    private static String amount(BigDecimal value) { return value.setScale(2).toPlainString(); }

    public record CustomerSnapshot(UUID id, String name, String shortName, String currencyCode) { }
    public record FinishedGoodSnapshot(
            UUID id, String productKind, String styleNo, String name, String size,
            String color, UUID uomId, String uomCode) { }
    public record ResolvedItem(
            boolean custom, UUID finishedGoodId, String productKind, String styleNo, String name,
            String size, String color, UUID uomId, String uomCode, BigDecimal orderQty,
            BigDecimal unitPrice, String currencyCode, BigDecimal amount, String remark) {
        public static ResolvedItem of(
                boolean custom, UUID finishedGoodId, String productKind, String styleNo, String name,
                String size, String color, UUID uomId, String uomCode, BigDecimal orderQty,
                BigDecimal unitPrice, String currencyCode, String remark) {
            BigDecimal amount = orderQty.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            if (amount.precision() - amount.scale() > 16) {
                throw new com.company.erp.api.ApiException(
                        com.company.erp.api.ApiErrorCode.VALIDATION_FAILED,
                        "Item amount exceeds the supported monetary range.");
            }
            return new ResolvedItem(custom, finishedGoodId, productKind, styleNo, name, size, color,
                    uomId, uomCode, orderQty, unitPrice, currencyCode, amount, remark);
        }
    }
    private record ReferenceState(String uomStatus, boolean currencyActive) { }
    private record ItemWithOrder(UUID orderId, BuyerOrderItemResponse item) { }
}
