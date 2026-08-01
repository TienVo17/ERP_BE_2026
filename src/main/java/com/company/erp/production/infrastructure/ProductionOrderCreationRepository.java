package com.company.erp.production.infrastructure;

import com.company.erp.sales.api.BuyerOrderModels.ProductionConfigurationResponse;
import com.company.erp.sales.api.BuyerOrderModels.ProductionEventResponse;
import com.company.erp.sales.api.BuyerOrderModels.ProductionOrderResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProductionOrderCreationRepository {

    private final JdbcClient jdbc;

    public ProductionOrderCreationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ProductionOrderResponse create(
            String productionNo, UUID buyerOrderId, UUID itemId, String productKind,
            String productNo, BigDecimal plannedQty, UUID actor, String reason) {
        UUID id = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO production.production_order (
                            id, production_no, buyer_order_item_id, buyer_order_id,
                            product_kind_snapshot, product_no, qr_value, planned_qty,
                            created_by, updated_by
                        ) VALUES (
                            :id, :productionNo, :itemId, :buyerOrderId, :productKind,
                            :productNo, :productionNo, :plannedQty, :actor, :actor
                        )
                        """).param("id", id).param("productionNo", productionNo)
                .param("itemId", itemId).param("buyerOrderId", buyerOrderId)
                .param("productKind", productKind).param("productNo", productNo)
                .param("plannedQty", plannedQty).param("actor", actor).update();
        jdbc.sql("""
                        INSERT INTO production.production_event (
                            id, production_order_id, event_type, actor_user_id, reason
                        ) VALUES (:id, :orderId, 'CREATED', :actor, :reason)
                        """).param("id", eventId).param("orderId", id)
                .param("actor", actor).param("reason", reason).update();
        return find(id);
    }

    public List<ProductionOrderResponse> findByBuyerOrder(UUID buyerOrderId) {
        return jdbc.sql("""
                        SELECT po.*, pg.group_no
                        FROM production.production_order po
                        LEFT JOIN production.production_group pg ON pg.id = po.production_group_id
                        WHERE po.buyer_order_id = :id
                        ORDER BY po.created_at, po.id
                        """).param("id", buyerOrderId).query((rs, row) -> map(rs)).list();
    }

    private ProductionOrderResponse find(UUID id) {
        return jdbc.sql("""
                        SELECT po.*, pg.group_no
                        FROM production.production_order po
                        LEFT JOIN production.production_group pg ON pg.id = po.production_group_id
                        WHERE po.id = :id
                        """).param("id", id).query((rs, row) -> map(rs)).single();
    }

    private ProductionOrderResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        List<ProductionEventResponse> events = jdbc.sql("""
                        SELECT id, event_type, actor_user_id, reason, occurred_at
                        FROM production.production_event
                        WHERE production_order_id = :id
                        ORDER BY occurred_at, id
                        """).param("id", id).query((event, row) -> new ProductionEventResponse(
                        event.getObject("id", UUID.class), event.getString("event_type"),
                        event.getObject("actor_user_id", UUID.class), event.getString("reason"),
                        event.getObject("occurred_at", OffsetDateTime.class).toInstant())).list();
        BigDecimal produced = rs.getBigDecimal("produced_qty");
        OffsetDateTime finished = rs.getObject("finished_at", OffsetDateTime.class);
        return new ProductionOrderResponse(
                id, rs.getLong("version"), rs.getString("production_no"), rs.getString("product_no"),
                rs.getString("qr_value"), rs.getObject("production_group_id", UUID.class),
                rs.getString("group_no"), rs.getObject("buyer_order_id", UUID.class),
                rs.getObject("buyer_order_item_id", UUID.class), rs.getString("product_kind_snapshot"),
                rs.getBigDecimal("planned_qty").setScale(4).toPlainString(),
                produced == null ? null : produced.setScale(4).toPlainString(), rs.getString("status"),
                ProductionConfigurationResponse.empty(rs.getString("product_kind_snapshot")),
                events, finished == null ? null : finished.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}
