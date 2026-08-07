package com.company.erp.inventory.infrastructure;

import com.company.erp.identity.application.AdminQuery;
import com.company.erp.inventory.api.StockModels.EligibleReturnSourcePageResponse;
import com.company.erp.inventory.api.StockModels.EligibleReturnSourceResponse;
import com.company.erp.inventory.api.StockModels.PageMetadata;
import com.company.erp.inventory.api.StockModels.StockMovementPageResponse;
import com.company.erp.inventory.api.StockModels.StockMovementResponse;
import com.company.erp.inventory.api.StockModels.StockPositionPageResponse;
import com.company.erp.production.api.ProductionModels.StockPositionResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class StockJdbcRepository {

    private static final Map<String, String> POSITION_SORT = Map.of(
            "currentQty", "sp.current_qty", "updatedAt", "sp.updated_at", "id", "sp.id");
    private static final Map<String, String> MOVEMENT_SORT = Map.of(
            "occurredAt", "sm.occurred_at", "businessDate", "sm.business_date", "id", "sm.id");
    private static final Map<String, String> RETURN_SORT = Map.of(
            "deliveryDate", "dn.delivery_date", "deliveryNo", "dn.delivery_no",
            "deliveryNoteItemId", "dni.id");
    private final JdbcClient jdbc;

    public StockJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void coordinate() {
        jdbc.sql("SELECT pg_catalog.pg_advisory_xact_lock(20260730, 1)")
                .query((result, row) -> Boolean.TRUE)
                .single();
    }

    public List<String> positionSort(List<String> sort) {
        return AdminQuery.sort(sort, POSITION_SORT, List.of("updatedAt,desc", "id,asc"));
    }

    public List<String> movementSort(List<String> sort) {
        return AdminQuery.sort(sort, MOVEMENT_SORT, List.of("occurredAt,asc", "id,asc"));
    }

    public List<String> returnSort(List<String> sort) {
        return AdminQuery.sort(sort, RETURN_SORT, List.of("deliveryDate,desc", "deliveryNoteItemId,asc"));
    }

    public StockPositionPageResponse list(
            int page, int size, UUID customerId, UUID productionOrderId, Boolean inStock, List<String> sort) {
        long total = positionStatement("SELECT count(*)", customerId, productionOrderId, inStock, "")
                .query(Long.class).single();
        List<StockPositionResponse> items = positionStatement("""
                        SELECT sp.*, u.code AS uom_code
                        """, customerId, productionOrderId, inStock,
                " ORDER BY " + AdminQuery.orderBy(sort, POSITION_SORT) + " LIMIT :limit OFFSET :offset")
                .param("limit", size).param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> position(rs)).list();
        return new StockPositionPageResponse(items, new PageMetadata(page, size, total,
                AdminQuery.totalPages(total, size)), AdminQuery.filters("customerId", customerId,
                "productionOrderId", productionOrderId, "inStock", inStock), sort);
    }

    public Optional<StockPositionResponse> find(UUID id) {
        return jdbc.sql("""
                        SELECT sp.*, u.code AS uom_code
                        FROM inventory.stock_position sp
                        JOIN master_data.uom u ON u.id = sp.uom_id
                        WHERE sp.id = :id
                        """).param("id", id).query((rs, row) -> position(rs)).optional();
    }

    public Optional<PositionRow> lock(UUID id) {
        return jdbc.sql("SELECT * FROM inventory.stock_position WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, row) -> row(rs)).optional();
    }

    public StockMovementPageResponse movements(
            UUID id, int page, int size, String type, java.time.LocalDate from,
            java.time.LocalDate to, List<String> sort) {
        long total = movementStatement("SELECT count(*)", id, type, from, to, "")
                .query(Long.class).single();
        List<StockMovementResponse> items = movementStatement("""
                        SELECT sm.*
                        """, id, type, from, to,
                " ORDER BY " + AdminQuery.orderBy(sort, MOVEMENT_SORT) + " LIMIT :limit OFFSET :offset")
                .param("limit", size).param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> movement(rs)).list();
        return new StockMovementPageResponse(items, new PageMetadata(page, size, total,
                AdminQuery.totalPages(total, size)), AdminQuery.filters("movementType", type,
                "businessDateFrom", from, "businessDateTo", to), sort);
    }

    public EligibleReturnSourcePageResponse eligibleReturns(
            UUID positionId, int page, int size, List<String> sort) {
        String from = """
                FROM delivery.delivery_note_item dni
                JOIN delivery.delivery_note dn ON dn.id = dni.delivery_note_id
                JOIN inventory.stock_position sp ON sp.id = dni.stock_position_id
                WHERE dni.stock_position_id = :positionId AND dn.status = 'POSTED'
                  AND dni.delivery_qty > COALESCE((
                      SELECT sum(sm.quantity_signed)
                      FROM inventory.stock_movement sm
                      WHERE sm.movement_type = 'RETURN'
                        AND sm.return_source_delivery_item_id = dni.id
                  ), 0)
                """;
        long total = jdbc.sql("SELECT count(*) " + from).param("positionId", positionId)
                .query(Long.class).single();
        List<EligibleReturnSourceResponse> items = jdbc.sql("""
                        SELECT dni.id, dn.delivery_no, dn.delivery_date, dni.delivery_qty,
                               dni.delivery_qty - COALESCE((
                                   SELECT sum(sm.quantity_signed)
                                   FROM inventory.stock_movement sm
                                   WHERE sm.movement_type = 'RETURN'
                                     AND sm.return_source_delivery_item_id = dni.id
                               ), 0) AS net_returnable_qty,
                               dni.uom_code_snapshot
                        """ + from + " ORDER BY " + AdminQuery.orderBy(sort, RETURN_SORT)
                        + " LIMIT :limit OFFSET :offset")
                .param("positionId", positionId).param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> new EligibleReturnSourceResponse(
                        rs.getObject("id", UUID.class), rs.getString("delivery_no"),
                        rs.getDate("delivery_date").toLocalDate(), quantity(rs.getBigDecimal("delivery_qty")),
                        quantity(rs.getBigDecimal("net_returnable_qty")), rs.getString("uom_code_snapshot")))
                .list();
        return new EligibleReturnSourcePageResponse(items, new PageMetadata(page, size, total,
                AdminQuery.totalPages(total, size)), Map.of(), sort);
    }

    public Optional<DeliverySourceRow> lockDeliverySource(UUID itemId) {
        return jdbc.sql("""
                        SELECT dni.id, dni.stock_position_id, dni.delivery_qty, dn.id AS delivery_note_id, dn.status
                        FROM delivery.delivery_note_item dni
                        JOIN delivery.delivery_note dn ON dn.id = dni.delivery_note_id
                        WHERE dni.id = :id
                        FOR UPDATE OF dni, dn
                        """).param("id", itemId).query((rs, row) -> new DeliverySourceRow(
                        rs.getObject("id", UUID.class), rs.getObject("delivery_note_id", UUID.class),
                        rs.getObject("stock_position_id", UUID.class), rs.getBigDecimal("delivery_qty"),
                        rs.getString("status"))).optional();
    }

    public BigDecimal returnedForSource(UUID itemId) {
        return jdbc.sql("""
                        SELECT COALESCE(sum(quantity_signed), 0)
                        FROM inventory.stock_movement
                        WHERE movement_type = 'RETURN' AND return_source_delivery_item_id = :id
                        """).param("id", itemId).query(BigDecimal.class).single();
    }

    public StockMovementResponse dispose(
            PositionRow position, BigDecimal quantity, java.time.LocalDate date,
            String reason, UUID commandId, UUID actor) {
        BigDecimal balance = position.currentQty().subtract(quantity);
        UUID movementId = UUID.randomUUID();
        updatePosition(position.id(), position.version(), balance, position.deliveredQty(),
                position.returnedQty(), position.disposedQty().add(quantity), actor);
        insertMovement(movementId, position.id(), "DISPOSE", quantity.negate(), balance, date,
                "STOCK_ADJUSTMENT", position.id(), null, null, commandId, reason, actor);
        return movement(movementId);
    }

    public StockMovementResponse recordReturn(
            PositionRow position, DeliverySourceRow source, BigDecimal quantity,
            java.time.LocalDate date, String reason, UUID commandId, UUID actor) {
        BigDecimal balance = position.currentQty().add(quantity);
        UUID movementId = UUID.randomUUID();
        updatePosition(position.id(), position.version(), balance, position.deliveredQty(),
                position.returnedQty().add(quantity), position.disposedQty(), actor);
        insertMovement(movementId, position.id(), "RETURN", quantity, balance, date,
                "DELIVERY_RETURN", source.deliveryNoteId(), source.id(), source.id(), commandId, reason, actor);
        return movement(movementId);
    }

    public StockMovementResponse movement(UUID id) {
        return jdbc.sql("SELECT * FROM inventory.stock_movement WHERE id = :id")
                .param("id", id).query((rs, row) -> movement(rs)).single();
    }

    public void forceDeferredConstraints() {
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        jdbc.sql("SET CONSTRAINTS ALL DEFERRED").update();
    }

    private void updatePosition(UUID id, long version, BigDecimal current, BigDecimal delivered,
            BigDecimal returned, BigDecimal disposed, UUID actor) {
        int updated = jdbc.sql("""
                        UPDATE inventory.stock_position
                        SET delivered_qty = :delivered, returned_qty = :returned,
                            disposed_qty = :disposed, current_qty = :current,
                            order_balance_qty = order_qty - :delivered + :returned,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version
                        """).param("delivered", delivered).param("returned", returned)
                .param("disposed", disposed).param("current", current).param("actor", actor)
                .param("id", id).param("version", version).update();
        if (updated != 1) throw new IllegalStateException("Locked Stock Position changed during command");
    }

    private void insertMovement(UUID id, UUID positionId, String type, BigDecimal quantity,
            BigDecimal balance, java.time.LocalDate date, String sourceType, UUID sourceId,
            UUID sourceItemId, UUID returnSource, UUID commandId, String reason, UUID actor) {
        jdbc.sql("""
                        INSERT INTO inventory.stock_movement (
                            id, stock_position_id, movement_type, quantity_signed, balance_after,
                            business_date, source_type, source_id, source_item_id,
                            return_source_delivery_item_id, idempotency_key, reason, created_by
                        ) VALUES (
                            :id, :positionId, :type, :quantity, :balance,
                            :businessDate, :sourceType, :sourceId, :sourceItemId,
                            :returnSource, :commandId, :reason, :actor
                        )
                        """).param("id", id).param("positionId", positionId).param("type", type)
                .param("quantity", quantity).param("balance", balance).param("businessDate", date)
                .param("sourceType", sourceType).param("sourceId", sourceId)
                .param("sourceItemId", sourceItemId, java.sql.Types.OTHER)
                .param("returnSource", returnSource, java.sql.Types.OTHER)
                .param("commandId", commandId.toString()).param("reason", reason).param("actor", actor).update();
    }

    private JdbcClient.StatementSpec positionStatement(String select, UUID customerId,
            UUID productionOrderId, Boolean inStock, String suffix) {
        return jdbc.sql(select + """
                         FROM inventory.stock_position sp
                         JOIN master_data.uom u ON u.id = sp.uom_id
                         WHERE (CAST(:customerId AS uuid) IS NULL OR sp.customer_id = :customerId)
                           AND (CAST(:productionId AS uuid) IS NULL OR sp.production_order_id = :productionId)
                           AND (CAST(:inStock AS boolean) IS NULL
                                OR (:inStock = true AND sp.current_qty > 0)
                                OR (:inStock = false AND sp.current_qty = 0))
                        """ + suffix).param("customerId", customerId).param("productionId", productionOrderId)
                .param("inStock", inStock);
    }

    private JdbcClient.StatementSpec movementStatement(String select, UUID positionId, String type,
            java.time.LocalDate from, java.time.LocalDate to, String suffix) {
        return jdbc.sql(select + """
                         FROM inventory.stock_movement sm
                         WHERE sm.stock_position_id = :positionId
                           AND (CAST(:type AS varchar) IS NULL OR sm.movement_type = :type)
                           AND (CAST(:dateFrom AS date) IS NULL OR sm.business_date >= :dateFrom)
                           AND (CAST(:dateTo AS date) IS NULL OR sm.business_date <= :dateTo)
                        """ + suffix).param("positionId", positionId).param("type", type)
                .param("dateFrom", from).param("dateTo", to);
    }

    private static StockPositionResponse position(ResultSet rs) throws SQLException {
        return new StockPositionResponse(rs.getObject("id", UUID.class), rs.getLong("version"),
                rs.getObject("production_order_id", UUID.class), rs.getObject("buyer_order_item_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getString("currency_code"), rs.getString("uom_code"),
                quantity(rs.getBigDecimal("order_qty")), quantity(rs.getBigDecimal("produced_qty")),
                quantity(rs.getBigDecimal("delivered_qty")), quantity(rs.getBigDecimal("returned_qty")),
                quantity(rs.getBigDecimal("disposed_qty")), quantity(rs.getBigDecimal("current_qty")),
                quantity(rs.getBigDecimal("order_balance_qty")),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static PositionRow row(ResultSet rs) throws SQLException {
        return new PositionRow(rs.getObject("id", UUID.class), rs.getLong("version"),
                rs.getBigDecimal("current_qty"), rs.getBigDecimal("delivered_qty"),
                rs.getBigDecimal("returned_qty"), rs.getBigDecimal("disposed_qty"));
    }

    private static StockMovementResponse movement(ResultSet rs) throws SQLException {
        return new StockMovementResponse(rs.getObject("id", UUID.class), rs.getString("movement_type"),
                signed(rs.getBigDecimal("quantity_signed")), quantity(rs.getBigDecimal("balance_after")),
                rs.getDate("business_date").toLocalDate(), rs.getString("source_type"),
                rs.getObject("source_id", UUID.class), rs.getObject("source_item_id", UUID.class),
                rs.getString("reason"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    private static String quantity(BigDecimal value) { return value.setScale(4).toPlainString(); }
    private static String signed(BigDecimal value) { return value.setScale(4).toPlainString(); }

    public record PositionRow(UUID id, long version, BigDecimal currentQty,
            BigDecimal deliveredQty, BigDecimal returnedQty, BigDecimal disposedQty) { }
    public record DeliverySourceRow(
            UUID id, UUID deliveryNoteId, UUID positionId, BigDecimal deliveryQty, String status) { }
}
