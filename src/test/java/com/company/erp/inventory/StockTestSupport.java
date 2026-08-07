package com.company.erp.inventory;

import com.company.erp.production.ProductionTestSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class StockTestSupport extends ProductionTestSupport {

    /**
     * Concurrency subclasses commit their fixtures, so this month must stay clear of the months
     * other suites own; otherwise the committed rate row collides with their unique monthly key.
     */
    private static final LocalDate DELIVERY_MONTH = LocalDate.of(2026, 9, 1);

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.transaction.PlatformTransactionManager fixtureTransactionManager;

    protected UUID finishedStock(String quantity) throws Exception {
        UUID processId = insertProcess("Stock process " + UUID.randomUUID(), "PROCESS-" + UUID.randomUUID());
        UUID buyerOrderId = confirmedOrder(List.of(standardItem(finishedGoodId, quantity)));
        UUID productionId = productionIds(buyerOrderId).getFirst();
        mockMvc.perform(put("/api/v1/production-orders/{id}/configuration", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header(IDEMPOTENCY, "stock-config-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json(printConfiguration(processId))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/production-orders/{id}/finish", productionId)
                        .with(saleJwt()).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .header(IDEMPOTENCY, "stock-finish-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "stock fixture"))))
                .andExpect(status().isOk());
        return jdbc.sql("SELECT id FROM inventory.stock_position WHERE production_order_id = :id")
                .param("id", productionId).query(UUID.class).single();
    }

    /**
     * The delivery integrity trigger is deferred, so the whole POSTED fixture has to land inside one
     * transaction. Concurrency tests run without an ambient transaction, so open one explicitly.
     */
    protected UUID postedDeliveryItem(UUID positionId, String quantity) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                fixtureTransactionManager);
        transaction.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
        return transaction.execute(status -> insertPostedDeliveryItem(positionId, quantity));
    }

    /** One rate row per month is unique in persistence, so reuse it across fixtures. */
    private UUID monthlyExchangeRate(LocalDate month) {
        return jdbc.sql("""
                        SELECT id FROM master_data.monthly_exchange_rate
                        WHERE effective_month = :month
                        """)
                .param("month", month.withDayOfMonth(1))
                .query(UUID.class)
                .optional()
                .orElseGet(() -> insertExchangeRate(month));
    }

    private UUID insertPostedDeliveryItem(UUID positionId, String quantity) {
        var source = jdbc.sql("""
                        SELECT sp.customer_id, sp.currency_code, sp.buyer_order_item_id,
                               sp.production_order_id, bo.id AS buyer_order_id,
                               bo.sys_po_no, bo.buyer_po, bo.pic_name_snapshot,
                               bo.po_date, bo.delivery_date, boi.product_kind_snapshot,
                               boi.style_no_snapshot, boi.name_snapshot, boi.size_snapshot,
                               boi.color_snapshot, boi.uom_code_snapshot, boi.order_qty,
                               sp.produced_qty, sp.current_qty, sp.version
                        FROM inventory.stock_position sp
                        JOIN sales.buyer_order_item boi ON boi.id = sp.buyer_order_item_id
                        JOIN sales.buyer_order bo ON bo.id = boi.buyer_order_id
                        WHERE sp.id = :id
                        """)
                .param("id", positionId)
                .query((rs, row) -> Map.<String, Object>ofEntries(
                        Map.entry("customerId", rs.getObject("customer_id", UUID.class)),
                        Map.entry("currencyCode", rs.getString("currency_code")),
                        Map.entry("itemId", rs.getObject("buyer_order_item_id", UUID.class)),
                        Map.entry("productionId", rs.getObject("production_order_id", UUID.class)),
                        Map.entry("buyerOrderId", rs.getObject("buyer_order_id", UUID.class)),
                        Map.entry("sysPoNo", rs.getString("sys_po_no")),
                        Map.entry("buyerPo", rs.getString("buyer_po")),
                        Map.entry("picName", rs.getString("pic_name_snapshot")),
                        Map.entry("poDate", rs.getDate("po_date").toLocalDate()),
                        Map.entry("deliveryDate", rs.getDate("delivery_date").toLocalDate()),
                        Map.entry("productKind", rs.getString("product_kind_snapshot")),
                        Map.entry("styleNo", rs.getString("style_no_snapshot")),
                        Map.entry("name", rs.getString("name_snapshot")),
                        Map.entry("uomCode", rs.getString("uom_code_snapshot")),
                        Map.entry("orderQty", rs.getBigDecimal("order_qty")),
                        Map.entry("producedQty", rs.getBigDecimal("produced_qty")),
                        Map.entry("currentQty", rs.getBigDecimal("current_qty"))))
                .single();
        UUID rateId = monthlyExchangeRate(DELIVERY_MONTH);
        UUID deliveryId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        java.math.BigDecimal delivered = new java.math.BigDecimal(quantity);
        java.math.BigDecimal current = (java.math.BigDecimal) source.get("currentQty");
        jdbc.sql("""
                        INSERT INTO delivery.delivery_note (
                            id, customer_id, customer_name_snapshot, delivery_date, currency_code,
                            vat_percent, total_qty, total_amount, created_by, updated_by
                        ) VALUES (:id, :customerId, 'Fixture Customer', DATE '2026-09-10', :currency,
                                  0, :qty, :qty, :actor, :actor)
                        """)
                .param("id", deliveryId).param("customerId", source.get("customerId"))
                .param("currency", source.get("currencyCode")).param("qty", delivered)
                .param("actor", SYSTEM_USER_ID).update();
        jdbc.sql("""
                        INSERT INTO delivery.delivery_note_item (
                            id, delivery_note_id, line_no, stock_position_id, buyer_order_id,
                            buyer_order_item_id, production_order_id, sys_po_no_snapshot,
                            buyer_po_snapshot, pic_name_snapshot, po_date_snapshot,
                            promised_delivery_date_snapshot, product_kind_snapshot, style_no_snapshot,
                            name_snapshot, uom_code_snapshot, currency_code, order_qty_snapshot,
                            produced_qty_snapshot, delivery_qty, unit_price, amount
                        ) VALUES (
                            :id, :deliveryId, 1, :positionId, :buyerOrderId,
                            :buyerOrderItemId, :productionOrderId, :sysPoNo,
                            :buyerPo, :picName, :poDate, :promisedDate, :productKind, :styleNo,
                            :name, :uomCode, :currency, :orderQty,
                            :producedQty, :qty, 1.000000, :qty
                        )
                        """)
                .param("id", itemId).param("deliveryId", deliveryId).param("positionId", positionId)
                .param("buyerOrderId", source.get("buyerOrderId")).param("buyerOrderItemId", source.get("itemId"))
                .param("productionOrderId", source.get("productionId")).param("sysPoNo", source.get("sysPoNo"))
                .param("buyerPo", source.get("buyerPo")).param("picName", source.get("picName"))
                .param("poDate", source.get("poDate")).param("promisedDate", source.get("deliveryDate"))
                .param("productKind", source.get("productKind")).param("styleNo", source.get("styleNo"))
                .param("name", source.get("name")).param("uomCode", source.get("uomCode"))
                .param("currency", source.get("currencyCode")).param("orderQty", source.get("orderQty"))
                .param("producedQty", source.get("producedQty")).param("qty", delivered).update();
        jdbc.sql("""
                        UPDATE inventory.stock_position
                        SET delivered_qty = delivered_qty + :qty,
                            current_qty = current_qty - :qty,
                            order_balance_qty = order_balance_qty - :qty,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id
                        """).param("qty", delivered).param("actor", SYSTEM_USER_ID).param("id", positionId).update();
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (
                            id, stock_position_id, movement_type, quantity_signed, balance_after,
                            business_date, source_type, source_id, source_item_id,
                            idempotency_key, created_by
                        ) VALUES (
                            :id, :positionId, 'DELIVERY', -:qty, :balance,
                            DATE '2026-09-10', 'DELIVERY', :deliveryId, :itemId,
                            :key, :actor
                        )
                        """).param("id", movementId).param("positionId", positionId).param("qty", delivered)
                .param("balance", current.subtract(delivered)).param("deliveryId", deliveryId)
                .param("itemId", itemId).param("key", "fixture-" + UUID.randomUUID())
                .param("actor", SYSTEM_USER_ID).update();
        jdbc.sql("UPDATE delivery.delivery_note_item SET delivery_movement_id = :movementId WHERE id = :id")
                .param("movementId", movementId).param("id", itemId).update();
        jdbc.sql("""
                        UPDATE delivery.delivery_note
                        SET delivery_no = :deliveryNo, exchange_rate_id = :rateId,
                            vnd_usd_rate_snapshot = 25000.000000, won_usd_rate_snapshot = 1300.000000,
                            status = 'POSTED', posted_at = clock_timestamp(), posted_by = :actor,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id
                        """).param("deliveryNo", "DN-2026-%06d".formatted(Math.floorMod(deliveryId.hashCode(), 1_000_000)))
                .param("rateId", rateId).param("actor", SYSTEM_USER_ID).param("id", deliveryId).update();
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        jdbc.sql("SET CONSTRAINTS ALL DEFERRED").update();
        return itemId;
    }
}
