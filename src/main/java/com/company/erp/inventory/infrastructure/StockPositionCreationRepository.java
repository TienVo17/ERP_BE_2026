package com.company.erp.inventory.infrastructure;

import com.company.erp.production.api.ProductionModels.StockPositionResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StockPositionCreationRepository {

    private final JdbcClient jdbc;

    public StockPositionCreationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public StockPositionResponse createForFinishedProduction(
            UUID productionOrderId,
            UUID commandId,
            LocalDate businessDate,
            UUID actor) {
        Source source = jdbc.sql("""
                        SELECT po.id AS production_order_id, po.buyer_order_item_id,
                               bo.customer_id, boi.currency_code, boi.uom_id,
                               boi.uom_code_snapshot, boi.order_qty, po.produced_qty
                        FROM production.production_order po
                        JOIN sales.buyer_order_item boi ON boi.id = po.buyer_order_item_id
                        JOIN sales.buyer_order bo ON bo.id = boi.buyer_order_id
                        WHERE po.id = :id AND po.status = 'FINISHED'
                        """)
                .param("id", productionOrderId)
                .query((rs, row) -> new Source(
                        rs.getObject("production_order_id", UUID.class),
                        rs.getObject("buyer_order_item_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("currency_code"),
                        rs.getObject("uom_id", UUID.class),
                        rs.getString("uom_code_snapshot"),
                        rs.getBigDecimal("order_qty"),
                        rs.getBigDecimal("produced_qty")))
                .single();
        UUID positionId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO inventory.stock_position (
                            id, production_order_id, buyer_order_item_id, customer_id,
                            currency_code, uom_id, order_qty, produced_qty,
                            current_qty, order_balance_qty, created_by, updated_by
                        ) VALUES (
                            :id, :productionOrderId, :buyerOrderItemId, :customerId,
                            :currencyCode, :uomId, :orderQty, :producedQty,
                            :producedQty, :orderQty, :actor, :actor
                        )
                        """)
                .param("id", positionId)
                .param("productionOrderId", source.productionOrderId())
                .param("buyerOrderItemId", source.buyerOrderItemId())
                .param("customerId", source.customerId())
                .param("currencyCode", source.currencyCode())
                .param("uomId", source.uomId())
                .param("orderQty", source.orderQty())
                .param("producedQty", source.producedQty())
                .param("actor", actor)
                .update();
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (
                            id, stock_position_id, movement_type, quantity_signed,
                            balance_after, business_date, source_type, source_id,
                            idempotency_key, created_by
                        ) VALUES (
                            :id, :positionId, 'PRODUCTION', :quantity,
                            :quantity, :businessDate, 'PRODUCTION', :productionOrderId,
                            :commandId, :actor
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("positionId", positionId)
                .param("quantity", source.producedQty())
                .param("businessDate", businessDate)
                .param("productionOrderId", source.productionOrderId())
                .param("commandId", commandId.toString())
                .param("actor", actor)
                .update();
        return find(positionId);
    }

    public StockPositionResponse find(UUID id) {
        return jdbc.sql("""
                        SELECT sp.*, u.code AS uom_code
                        FROM inventory.stock_position sp
                        JOIN master_data.uom u ON u.id = sp.uom_id
                        WHERE sp.id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> new StockPositionResponse(
                        rs.getObject("id", UUID.class),
                        rs.getLong("version"),
                        rs.getObject("production_order_id", UUID.class),
                        rs.getObject("buyer_order_item_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("currency_code"),
                        rs.getString("uom_code"),
                        quantity(rs.getBigDecimal("order_qty")),
                        quantity(rs.getBigDecimal("produced_qty")),
                        quantity(rs.getBigDecimal("delivered_qty")),
                        quantity(rs.getBigDecimal("returned_qty")),
                        quantity(rs.getBigDecimal("disposed_qty")),
                        quantity(rs.getBigDecimal("current_qty")),
                        quantity(rs.getBigDecimal("order_balance_qty")),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private static String quantity(BigDecimal value) {
        return value.setScale(4).toPlainString();
    }

    private record Source(
            UUID productionOrderId,
            UUID buyerOrderItemId,
            UUID customerId,
            String currencyCode,
            UUID uomId,
            String uomCode,
            BigDecimal orderQty,
            BigDecimal producedQty) {
    }
}
