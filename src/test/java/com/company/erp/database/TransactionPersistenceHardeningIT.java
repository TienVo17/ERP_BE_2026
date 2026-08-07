package com.company.erp.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.erp.support.PostgresTestConfiguration;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class TransactionPersistenceHardeningIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEEDED_UOM_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Autowired
    private JdbcClient jdbc;

    @Test
    @Transactional
    void allocatesProductionGroupNumbersMonotonicallyAfterAGroupIsDeleted() {
        UUID orderId = insertOrderWithItem();
        String first = nextGroupNumber(orderId);

        jdbc.sql("""
                        INSERT INTO production.production_group (
                            id, buyer_order_id, group_no, created_by, updated_by
                        ) VALUES (:id, :buyerOrderId, :groupNo, :actor, :actor)
                        """)
                .param("id", UUID.randomUUID())
                .param("buyerOrderId", orderId)
                .param("groupNo", first)
                .param("actor", SYSTEM_USER_ID)
                .update();
        jdbc.sql("DELETE FROM production.production_group WHERE buyer_order_id = :buyerOrderId")
                .param("buyerOrderId", orderId)
                .update();
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();

        assertThat(first).endsWith("-001");
        assertThat(nextGroupNumber(orderId)).isEqualTo(first.substring(0, first.length() - 3) + "002");
    }

    @Test
    @Transactional
    void rejectsAnIncorrectIntermediateBalanceWhenTheAggregateAndFinalBalanceReconcile() {
        UUID orderId = insertOrderWithItem();
        UUID itemId = jdbc.sql("SELECT id FROM sales.buyer_order_item WHERE buyer_order_id = :buyerOrderId")
                .param("buyerOrderId", orderId)
                .query(UUID.class)
                .single();
        UUID productionOrderId = insertFinishedProductionOrder(orderId, itemId);
        UUID positionId = insertStockPosition(orderId, itemId, productionOrderId);

        insertMovement(positionId, "PRODUCTION", new BigDecimal("7"), new BigDecimal("999"),
                "PRODUCTION", null, null, "production-intermediate", "2099-01-01 00:00:00+00");
        insertMovement(positionId, "PRODUCTION", new BigDecimal("3"), new BigDecimal("10"),
                "PRODUCTION", null, null, "production-final", "2099-01-01 00:00:01+00");

        assertThatThrownBy(() -> jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update())
                .isInstanceOf(DataAccessException.class);
    }

    private String nextGroupNumber(UUID buyerOrderId) {
        return jdbc.sql("SELECT production.next_production_group_no(:buyerOrderId)")
                .param("buyerOrderId", buyerOrderId)
                .query(String.class)
                .single();
    }

    private UUID insertOrderWithItem() {
        UUID customerId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.customer (
                            id, short_name, name, currency_code, created_by, updated_by
                        ) VALUES (:id, :shortName, 'Persistence Customer', 'USD', :actor, :actor)
                        """)
                .param("id", customerId)
                .param("shortName", "PERSIST-" + customerId.toString().substring(0, 8))
                .param("actor", SYSTEM_USER_ID)
                .update();

        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO sales.buyer_order (
                            id, sys_po_no, order_type, customer_id, customer_name_snapshot,
                            customer_short_name_snapshot, pic_source, pic_name_snapshot, buyer_po,
                            po_date, delivery_date, created_by, updated_by
                        ) VALUES (
                            :id, :sysPoNo, 'STANDARD', :customerId, 'Persistence Customer', 'PERSIST',
                            'CUSTOM', 'PIC', 'PO', CURRENT_DATE, CURRENT_DATE, :actor, :actor
                        )
                        """)
                .param("id", orderId)
                .param("sysPoNo", "SO-2099-%06d".formatted(Math.floorMod(orderId.hashCode(), 1_000_000)))
                .param("customerId", customerId)
                .param("actor", SYSTEM_USER_ID)
                .update();
        jdbc.sql("""
                        INSERT INTO sales.buyer_order_item (
                            id, buyer_order_id, line_no, is_custom, product_kind_snapshot,
                            style_no_snapshot, name_snapshot, uom_id, uom_code_snapshot, order_qty,
                            production_qty, unit_price, currency_code, amount, created_by, updated_by
                        ) VALUES (
                            :id, :buyerOrderId, 1, true, 'PRINT', 'STYLE', 'Persistence Item', :uomId, 'EA', 10,
                            10, 0, 'USD', 0, :actor, :actor
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("buyerOrderId", orderId)
                .param("uomId", SEEDED_UOM_ID)
                .param("actor", SYSTEM_USER_ID)
                .update();
        jdbc.sql("UPDATE sales.buyer_order SET status = 'CONFIRMED', updated_by = :actor WHERE id = :id")
                .param("actor", SYSTEM_USER_ID)
                .param("id", orderId)
                .update();
        return orderId;
    }

    private UUID insertFinishedProductionOrder(UUID buyerOrderId, UUID itemId) {
        UUID productionOrderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO production.production_order (
                            id, production_no, buyer_order_item_id, buyer_order_id, product_kind_snapshot,
                            product_no, qr_value, planned_qty, produced_qty, status, finished_at, finished_by,
                            created_by, updated_by
                        ) VALUES (
                            :id, :productionNo, :itemId, :buyerOrderId, 'PRINT', 'PERSIST-PRODUCT', :qrValue,
                            10, NULL, 'OPEN', NULL, NULL, :actor, :actor
                        )
                        """)
                .param("id", productionOrderId)
                .param("productionNo", "PR-2099-%06d".formatted(Math.floorMod(productionOrderId.hashCode(), 1_000_000)))
                .param("itemId", itemId)
                .param("buyerOrderId", buyerOrderId)
                .param("qrValue", "QR-" + productionOrderId)
                .param("actor", SYSTEM_USER_ID)
                .update();
        jdbc.sql("""
                        INSERT INTO production.production_print_config (production_order_id, updated_by)
                        VALUES (:productionOrderId, :actor)
                        """)
                .param("productionOrderId", productionOrderId)
                .param("actor", SYSTEM_USER_ID)
                .update();
        jdbc.sql("""
                        UPDATE production.production_order
                        SET status = 'FINISHED', produced_qty = planned_qty, finished_at = clock_timestamp(),
                            finished_by = :actor, updated_by = :actor
                        WHERE id = :productionOrderId
                        """)
                .param("productionOrderId", productionOrderId)
                .param("actor", SYSTEM_USER_ID)
                .update();
        return productionOrderId;
    }

    private UUID insertStockPosition(UUID orderId, UUID itemId, UUID productionOrderId) {
        UUID positionId = UUID.randomUUID();
        UUID customerId = jdbc.sql("SELECT customer_id FROM sales.buyer_order WHERE id = :orderId")
                .param("orderId", orderId)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                        INSERT INTO inventory.stock_position (
                            id, production_order_id, buyer_order_item_id, customer_id, currency_code, uom_id,
                            order_qty, produced_qty, delivered_qty, returned_qty, disposed_qty, current_qty,
                            order_balance_qty, created_by, updated_by
                        ) VALUES (
                            :id, :productionOrderId, :itemId, :customerId, 'USD', :uomId,
                            10, 10, 0, 0, 0, 10, 10, :actor, :actor
                        )
                        """)
                .param("id", positionId)
                .param("productionOrderId", productionOrderId)
                .param("itemId", itemId)
                .param("customerId", customerId)
                .param("uomId", SEEDED_UOM_ID)
                .param("actor", SYSTEM_USER_ID)
                .update();
        return positionId;
    }

    private void insertMovement(
            UUID positionId,
            String movementType,
            BigDecimal quantity,
            BigDecimal balanceAfter,
            String sourceType,
            UUID sourceItemId,
            UUID returnSourceDeliveryItemId,
            String key,
            String occurredAt) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (
                            id, stock_position_id, movement_type, quantity_signed, balance_after, business_date,
                            source_type, source_id, source_item_id, return_source_delivery_item_id, idempotency_key,
                            reason, created_by, occurred_at
                        ) VALUES (
                            :id, :positionId, :movementType, :quantity, :balanceAfter, CURRENT_DATE,
                            :sourceType, :sourceId, :sourceItemId, :returnSourceDeliveryItemId, :key,
                            :reason, :actor, CAST(:occurredAt AS timestamptz)
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("positionId", positionId)
                .param("movementType", movementType)
                .param("quantity", quantity)
                .param("balanceAfter", balanceAfter)
                .param("sourceType", sourceType)
                .param("sourceId", UUID.randomUUID())
                .param("sourceItemId", sourceItemId, java.sql.Types.OTHER)
                .param("returnSourceDeliveryItemId", returnSourceDeliveryItemId, java.sql.Types.OTHER)
                .param("key", key + '-' + UUID.randomUUID())
                .param("reason", movementType.equals("PRODUCTION") ? null : movementType.toLowerCase(), java.sql.Types.VARCHAR)
                .param("actor", SYSTEM_USER_ID)
                .param("occurredAt", occurredAt)
                .update();
    }
}
