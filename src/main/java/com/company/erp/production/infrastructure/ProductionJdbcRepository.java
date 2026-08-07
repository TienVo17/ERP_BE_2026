package com.company.erp.production.infrastructure;

import com.company.erp.identity.application.AdminQuery;
import com.company.erp.production.api.ProductionModels.ProductionConfigurationRequest;
import com.company.erp.production.api.ProductionModels.ProductionConfigurationResponse;
import com.company.erp.production.api.ProductionModels.ProductionEventResponse;
import com.company.erp.production.api.ProductionModels.ProductionEventPageResponse;
import com.company.erp.production.api.ProductionModels.ProductionGroupMemberResponse;
import com.company.erp.production.api.ProductionModels.ProductionGroupResponse;
import com.company.erp.production.api.ProductionModels.ProductionOrderResponse;
import com.company.erp.production.api.ProductionModels.ProductionProcessResponse;
import com.company.erp.production.api.ProductionModels.ProductionYarnLineResponse;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
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
public class ProductionJdbcRepository {

    private static final Map<String, String> ORDER_SORT = Map.of(
            "productionNo", "po.production_no", "status", "po.status", "createdAt", "po.created_at", "id", "po.id");
    private static final Map<String, String> EVENT_SORT = Map.of("occurredAt", "occurred_at", "id", "id");
    private static final String SELECT = """
            SELECT po.*, pg.group_no
            FROM production.production_order po
            LEFT JOIN production.production_group pg ON pg.id = po.production_group_id
            """;
    private final JdbcClient jdbc;

    public ProductionJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void coordinate() {
        jdbc.sql("SELECT pg_catalog.pg_advisory_xact_lock(20260730, 1)")
                .query((rs, row) -> Boolean.TRUE).single();
    }

    public List<String> sort(List<String> requested) {
        return AdminQuery.sort(requested, ORDER_SORT, List.of("productionNo,asc", "id,asc"));
    }

    public List<String> eventSort(List<String> requested) {
        return AdminQuery.sort(requested, EVENT_SORT, List.of("occurredAt,asc", "id,asc"));
    }

    public long count(String status, UUID buyerOrderId, String productionNo, String productKind, String groupNo) {
        return filtered(jdbc.sql("SELECT count(*) FROM production.production_order po LEFT JOIN production.production_group pg ON pg.id = po.production_group_id"),
                status, buyerOrderId, productionNo, productKind, groupNo).query(Long.class).single();
    }

    public List<ProductionOrderResponse> list(int page, int size, String status, UUID buyerOrderId,
            String productionNo, String productKind, String groupNo, List<String> sort) {
        List<ProductionOrderResponse> headers = filtered(jdbc.sql(SELECT + filter() + " ORDER BY "
                        + AdminQuery.orderBy(sort, ORDER_SORT) + " LIMIT :limit OFFSET :offset"),
                status, buyerOrderId, productionNo, productKind, groupNo)
                .param("limit", size).param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> header(rs)).list();
        return enrich(headers);
    }

    public Optional<ProductionOrderResponse> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE po.id = :id").param("id", id)
                .query((rs, row) -> header(rs)).optional().map(value -> enrich(List.of(value)).getFirst());
    }

    public Optional<ProductionOrderResponse> lock(UUID id) {
        return jdbc.sql(SELECT + " WHERE po.id = :id FOR UPDATE OF po")
                .param("id", id)
                .query((rs, row) -> header(rs))
                .optional()
                .map(value -> enrich(List.of(value)).getFirst());
    }

    public List<UUID> lockActiveProcesses(List<UUID> ids) {
        return jdbc.sql("""
                        SELECT id
                        FROM master_data.process_master
                        WHERE id IN (:ids) AND status = 'ACTIVE'
                        ORDER BY id
                        FOR NO KEY UPDATE
                        """)
                .param("ids", ids)
                .query(UUID.class)
                .list();
    }

    public void replaceConfiguration(UUID id, ProductionConfigurationRequest request, UUID actor) {
        deleteConfiguration(id);
        if ("PRINT".equals(request.productKind())) {
            jdbc.sql("""
                            INSERT INTO production.production_print_config (
                                production_order_id, material_source, order_kind, remark, updated_by
                            ) VALUES (:id, :materialSource, :orderKind, :remark, :actor)
                            """)
                    .param("id", id)
                    .param("materialSource", request.materialSource(), java.sql.Types.VARCHAR)
                    .param("orderKind", request.orderKind(), java.sql.Types.VARCHAR)
                    .param("remark", request.remark(), java.sql.Types.VARCHAR)
                    .param("actor", actor)
                    .update();
        } else {
            jdbc.sql("""
                            INSERT INTO production.production_woven_config (
                                production_order_id, bim, ten_dia, mat_do, pick,
                                x_ngang_mm, y_doc_mm, hai_da_mm, logo_x_mm, logo_y_mm,
                                ho_percent, remark, updated_by
                            ) VALUES (
                                :id, :bim, :tenDia, :matDo, :pick,
                                :xNgang, :yDoc, :haiDa, :logoX, :logoY,
                                :hoPercent, :remark, :actor
                            )
                            """)
                    .param("id", id)
                    .param("bim", request.bim(), java.sql.Types.VARCHAR)
                    .param("tenDia", request.tenDia(), java.sql.Types.VARCHAR)
                    .param("matDo", request.matDo(), java.sql.Types.VARCHAR)
                    .param("pick", request.pick(), java.sql.Types.VARCHAR)
                    .param("xNgang", decimal(request.xNgangMm()), java.sql.Types.NUMERIC)
                    .param("yDoc", decimal(request.yDocMm()), java.sql.Types.NUMERIC)
                    .param("haiDa", decimal(request.haiDaMm()), java.sql.Types.NUMERIC)
                    .param("logoX", decimal(request.logoXMm()), java.sql.Types.NUMERIC)
                    .param("logoY", decimal(request.logoYMm()), java.sql.Types.NUMERIC)
                    .param("hoPercent", decimal(request.hoPercent()), java.sql.Types.NUMERIC)
                    .param("remark", request.remark(), java.sql.Types.VARCHAR)
                    .param("actor", actor)
                    .update();
            if (request.weaveTypes() != null) {
                for (String weaveType : request.weaveTypes().stream().sorted().toList()) {
                    jdbc.sql("""
                                    INSERT INTO production.production_woven_weave_type (
                                        production_order_id, weave_type
                                    ) VALUES (:id, :weaveType)
                                    """)
                            .param("id", id)
                            .param("weaveType", weaveType)
                            .update();
                }
            }
            int lineNo = 1;
            for (var yarnLine : request.yarnLines()) {
                jdbc.sql("""
                                INSERT INTO production.production_woven_yarn_line (
                                    id, production_order_id, line_no, yarn, yarn_code, denier
                                ) VALUES (:lineId, :id, :lineNo, :yarn, :yarnCode, :denier)
                                """)
                        .param("lineId", UUID.randomUUID())
                        .param("id", id)
                        .param("lineNo", lineNo++)
                        .param("yarn", yarnLine.yarn(), java.sql.Types.VARCHAR)
                        .param("yarnCode", yarnLine.yarnCode(), java.sql.Types.VARCHAR)
                        .param("denier", yarnLine.denier(), java.sql.Types.VARCHAR)
                        .update();
            }
        }
        for (var process : request.processes().stream()
                .sorted(java.util.Comparator.comparingInt(value -> value.sequenceNo())).toList()) {
            jdbc.sql("""
                            INSERT INTO production.production_order_process (
                                production_order_id, process_id, sequence_no, speed
                            ) VALUES (:id, :processId, :sequenceNo, :speed)
                            """)
                    .param("id", id)
                    .param("processId", process.processId())
                    .param("sequenceNo", process.sequenceNo())
                    .param("speed", decimal(process.speed()), java.sql.Types.NUMERIC)
                    .update();
        }
    }

    public boolean hasMatchingConfiguration(UUID id, String productKind) {
        String table = "PRINT".equals(productKind)
                ? "production.production_print_config"
                : "production.production_woven_config";
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE production_order_id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    public void finish(UUID id, long version, UUID actor) {
        int updated = jdbc.sql("""
                        UPDATE production.production_order
                        SET status = 'FINISHED', produced_qty = planned_qty,
                            finished_at = clock_timestamp(), finished_by = :actor,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'OPEN'
                        """)
                .param("id", id)
                .param("version", version)
                .param("actor", actor)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Locked Production Order changed during finish");
        }
    }

    public void incrementVersion(UUID id, long version, UUID actor) {
        int updated = jdbc.sql("""
                        UPDATE production.production_order
                        SET version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version AND status = 'OPEN'
                        """)
                .param("id", id)
                .param("version", version)
                .param("actor", actor)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Locked Production Order changed during configuration");
        }
    }

    public List<ProductionOrderResponse> lockAll(List<UUID> ids) {
        List<ProductionOrderResponse> locked = jdbc.sql(SELECT + " WHERE po.id IN (:ids) ORDER BY po.id FOR UPDATE OF po")
                .param("ids", ids).query((rs, row) -> header(rs)).list();
        return enrich(locked);
    }

    public Optional<GroupRow> lockGroup(UUID id) {
        return jdbc.sql("SELECT id, version, group_no, buyer_order_id FROM production.production_group WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, row) -> new GroupRow(rs.getObject("id", UUID.class), rs.getLong("version"),
                        rs.getString("group_no"), rs.getObject("buyer_order_id", UUID.class))).optional();
    }

    public List<ProductionOrderResponse> lockGroupMembers(UUID groupId) {
        List<ProductionOrderResponse> rows = jdbc.sql(SELECT + " WHERE po.production_group_id = :groupId ORDER BY po.id FOR UPDATE OF po")
                .param("groupId", groupId).query((rs, row) -> header(rs)).list();
        return enrich(rows);
    }

    public String nextGroupNo(UUID buyerOrderId) {
        return jdbc.sql("SELECT production.next_production_group_no(:id)").param("id", buyerOrderId)
                .query(String.class).single();
    }

    public UUID createGroup(UUID buyerOrderId, String groupNo, UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO production.production_group (id, buyer_order_id, group_no, created_by, updated_by)
                        VALUES (:id, :buyerOrderId, :groupNo, :actor, :actor)
                        """)
                .param("id", id).param("buyerOrderId", buyerOrderId).param("groupNo", groupNo).param("actor", actor).update();
        return id;
    }

    public void assignGroup(List<UUID> ids, UUID groupId, UUID actor, String reason) {
        for (UUID id : ids) {
            jdbc.sql("""
                            UPDATE production.production_order
                            SET production_group_id = :groupId, version = version + 1, updated_by = :actor
                            WHERE id = :id AND status = 'OPEN' AND production_group_id IS NULL
                            """)
                    .param("groupId", groupId).param("actor", actor).param("id", id).update();
            event(id, "GROUPED", actor, reason);
        }
    }

    public void clearGroup(List<UUID> ids, UUID groupId, UUID actor, String reason) {
        for (UUID id : ids) {
            jdbc.sql("""
                            UPDATE production.production_order
                            SET production_group_id = NULL, version = version + 1, updated_by = :actor
                            WHERE id = :id AND production_group_id = :groupId AND status = 'OPEN'
                            """)
                    .param("id", id).param("groupId", groupId).param("actor", actor).update();
            event(id, "UNGROUPED", actor, reason);
        }
    }

    public void deleteEmptyGroup(UUID groupId, long version) {
        jdbc.sql("""
                        DELETE FROM production.production_group pg
                        WHERE pg.id = :id AND pg.version = :version
                          AND NOT EXISTS (SELECT 1 FROM production.production_order po WHERE po.production_group_id = pg.id)
                        """)
                .param("id", groupId).param("version", version).update();
    }

    public ProductionGroupResponse group(UUID id) {
        GroupRow header = jdbc.sql("SELECT id, version, group_no, buyer_order_id FROM production.production_group WHERE id = :id")
                .param("id", id).query((rs, row) -> new GroupRow(rs.getObject("id", UUID.class), rs.getLong("version"),
                        rs.getString("group_no"), rs.getObject("buyer_order_id", UUID.class))).single();
        List<ProductionOrderResponse> members = jdbc.sql(SELECT + " WHERE po.production_group_id = :id ORDER BY po.id")
                .param("id", id).query((rs, row) -> header(rs)).list();
        return new ProductionGroupResponse(header.id(), header.version(), header.groupNo(), header.buyerOrderId(),
                enrich(members).stream().map(ProductionGroupMemberResponse::from).toList());
    }

    public ProductionGroupResponse removedGroup(GroupRow group, List<ProductionOrderResponse> members) {
        return new ProductionGroupResponse(group.id(), group.version(), group.groupNo(), group.buyerOrderId(),
                members.stream().map(ProductionGroupMemberResponse::from).toList());
    }

    public ProductionEventPageResponse events(UUID id, int page, int size, List<String> sort) {
        long count = jdbc.sql("SELECT count(*) FROM production.production_event WHERE production_order_id = :id")
                .param("id", id).query(Long.class).single();
        List<ProductionEventResponse> events = jdbc.sql("SELECT id, event_type, actor_user_id, reason, occurred_at FROM production.production_event WHERE production_order_id = :id ORDER BY "
                        + AdminQuery.orderBy(sort, EVENT_SORT) + " LIMIT :limit OFFSET :offset")
                .param("id", id).param("limit", size).param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> eventResponse(rs)).list();
        return new ProductionEventPageResponse(events, new com.company.erp.production.api.ProductionModels.PageMetadata(page, size, count, AdminQuery.totalPages(count, size)), Map.of(), sort);
    }

    public void forceDeferredConstraints() {
        jdbc.sql("SET CONSTRAINTS ALL IMMEDIATE").update();
        jdbc.sql("SET CONSTRAINTS ALL DEFERRED").update();
    }

    private List<ProductionOrderResponse> enrich(List<ProductionOrderResponse> headers) {
        if (headers.isEmpty()) return headers;
        List<UUID> ids = headers.stream().map(ProductionOrderResponse::id).toList();
        Map<UUID, List<ProductionEventResponse>> events = new LinkedHashMap<>();
        jdbc.sql("SELECT id, production_order_id, event_type, actor_user_id, reason, occurred_at FROM production.production_event WHERE production_order_id IN (:ids) ORDER BY production_order_id, occurred_at, id")
                .param("ids", ids).query((rs, row) -> new EventWithOrder(rs.getObject("production_order_id", UUID.class), eventResponse(rs))).list()
                .forEach(row -> events.computeIfAbsent(row.orderId(), ignored -> new ArrayList<>()).add(row.event()));
        Map<UUID, ProductionConfigurationResponse> configurations = configurations(headers);
        return headers.stream().map(value -> new ProductionOrderResponse(value.id(), value.version(), value.productionNo(), value.productNo(), value.qrValue(),
                value.groupId(), value.groupNo(), value.buyerOrderId(), value.buyerOrderItemId(), value.productKind(), value.plannedQty(), value.producedQty(), value.status(),
                configurations.get(value.id()), events.getOrDefault(value.id(), List.of()), value.finishedAt(), value.createdAt(), value.updatedAt())).toList();
    }

    private Map<UUID, ProductionConfigurationResponse> configurations(List<ProductionOrderResponse> headers) {
        List<UUID> ids = headers.stream().map(ProductionOrderResponse::id).toList();
        Map<UUID, ProductionConfigurationResponse> configurations = new LinkedHashMap<>();
        headers.forEach(header -> configurations.put(
                header.id(), ProductionConfigurationResponse.empty(header.productKind())));
        jdbc.sql("""
                        SELECT production_order_id, material_source, order_kind, remark
                        FROM production.production_print_config
                        WHERE production_order_id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, row) -> new ConfigWithOrder(
                        rs.getObject("production_order_id", UUID.class),
                        new ProductionConfigurationResponse("PRINT", rs.getString("material_source"),
                                rs.getString("order_kind"), null, null, null, null, null, null,
                                null, null, null, null, List.of(), List.of(), List.of(),
                                rs.getString("remark"))))
                .list().forEach(value -> configurations.put(value.orderId(), value.configuration()));
        jdbc.sql("""
                        SELECT production_order_id, bim, ten_dia, mat_do, pick, x_ngang_mm,
                               y_doc_mm, hai_da_mm, logo_x_mm, logo_y_mm, ho_percent, remark
                        FROM production.production_woven_config
                        WHERE production_order_id IN (:ids)
                        """)
                .param("ids", ids)
                .query((rs, row) -> new ConfigWithOrder(
                        rs.getObject("production_order_id", UUID.class),
                        new ProductionConfigurationResponse("WOVEN", null, null,
                                rs.getString("bim"), rs.getString("ten_dia"), rs.getString("mat_do"),
                                rs.getString("pick"), nullableQuantity(rs.getBigDecimal("x_ngang_mm")),
                                nullableQuantity(rs.getBigDecimal("y_doc_mm")),
                                nullableQuantity(rs.getBigDecimal("hai_da_mm")),
                                nullableQuantity(rs.getBigDecimal("logo_x_mm")),
                                nullableQuantity(rs.getBigDecimal("logo_y_mm")),
                                nullableQuantity(rs.getBigDecimal("ho_percent")), List.of(), List.of(),
                                List.of(), rs.getString("remark"))))
                .list().forEach(value -> configurations.put(value.orderId(), value.configuration()));
        Map<UUID, List<String>> weaveTypes = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT production_order_id, weave_type
                        FROM production.production_woven_weave_type
                        WHERE production_order_id IN (:ids)
                        ORDER BY production_order_id, weave_type
                        """).param("ids", ids)
                .query((rs, row) -> Map.entry(rs.getObject("production_order_id", UUID.class),
                        rs.getString("weave_type"))).list()
                .forEach(value -> weaveTypes.computeIfAbsent(value.getKey(), ignored -> new ArrayList<>())
                        .add(value.getValue()));
        Map<UUID, List<ProductionProcessResponse>> processes = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT production_order_id, process_id, sequence_no, speed
                        FROM production.production_order_process
                        WHERE production_order_id IN (:ids)
                        ORDER BY production_order_id, sequence_no, process_id
                        """).param("ids", ids)
                .query((rs, row) -> new ProcessWithOrder(rs.getObject("production_order_id", UUID.class),
                        new ProductionProcessResponse(rs.getObject("process_id", UUID.class),
                                rs.getInt("sequence_no"), nullableQuantity(rs.getBigDecimal("speed")))))
                .list().forEach(value -> processes.computeIfAbsent(value.orderId(), ignored -> new ArrayList<>())
                        .add(value.process()));
        Map<UUID, List<ProductionYarnLineResponse>> yarnLines = new LinkedHashMap<>();
        jdbc.sql("""
                        SELECT production_order_id, yarn, yarn_code, denier
                        FROM production.production_woven_yarn_line
                        WHERE production_order_id IN (:ids)
                        ORDER BY production_order_id, line_no, id
                        """).param("ids", ids)
                .query((rs, row) -> new YarnWithOrder(rs.getObject("production_order_id", UUID.class),
                        new ProductionYarnLineResponse(rs.getString("yarn"), rs.getString("yarn_code"),
                                rs.getString("denier"))))
                .list().forEach(value -> yarnLines.computeIfAbsent(value.orderId(), ignored -> new ArrayList<>())
                        .add(value.yarnLine()));
        configurations.replaceAll((id, configuration) -> new ProductionConfigurationResponse(
                configuration.productKind(), configuration.materialSource(), configuration.orderKind(),
                configuration.bim(), configuration.tenDia(), configuration.matDo(), configuration.pick(),
                configuration.xNgangMm(), configuration.yDocMm(), configuration.haiDaMm(),
                configuration.logoXMm(), configuration.logoYMm(), configuration.hoPercent(),
                weaveTypes.getOrDefault(id, List.of()), processes.getOrDefault(id, List.of()),
                yarnLines.getOrDefault(id, List.of()), configuration.remark()));
        return configurations;
    }

    private void deleteConfiguration(UUID id) {
        jdbc.sql("DELETE FROM production.production_order_process WHERE production_order_id = :id")
                .param("id", id).update();
        jdbc.sql("DELETE FROM production.production_woven_weave_type WHERE production_order_id = :id")
                .param("id", id).update();
        jdbc.sql("DELETE FROM production.production_woven_yarn_line WHERE production_order_id = :id")
                .param("id", id).update();
        jdbc.sql("DELETE FROM production.production_print_config WHERE production_order_id = :id")
                .param("id", id).update();
        jdbc.sql("DELETE FROM production.production_woven_config WHERE production_order_id = :id")
                .param("id", id).update();
    }

    private static JdbcClient.StatementSpec filtered(JdbcClient.StatementSpec statement, String status, UUID buyerOrderId,
            String productionNo, String productKind, String groupNo) {
        return statement.param("status", status).param("buyerOrderId", buyerOrderId)
                .param("productionNo", productionNo == null ? null : "%" + productionNo.trim() + "%")
                .param("productKind", productKind).param("groupNo", groupNo == null ? null : "%" + groupNo.trim() + "%");
    }

    private static String filter() {
        return """
                WHERE (CAST(:status AS varchar) IS NULL OR po.status = :status)
                  AND (CAST(:buyerOrderId AS uuid) IS NULL OR po.buyer_order_id = :buyerOrderId)
                  AND (CAST(:productionNo AS varchar) IS NULL OR upper(po.production_no) LIKE upper(:productionNo))
                  AND (CAST(:productKind AS varchar) IS NULL OR po.product_kind_snapshot = :productKind)
                  AND (CAST(:groupNo AS varchar) IS NULL OR upper(pg.group_no) LIKE upper(:groupNo))
                """;
    }

    private static ProductionOrderResponse header(ResultSet rs) throws SQLException {
        OffsetDateTime finishedAt = rs.getObject("finished_at", OffsetDateTime.class);
        return new ProductionOrderResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("production_no"),
                rs.getString("product_no"), rs.getString("qr_value"), rs.getObject("production_group_id", UUID.class), rs.getString("group_no"),
                rs.getObject("buyer_order_id", UUID.class), rs.getObject("buyer_order_item_id", UUID.class), rs.getString("product_kind_snapshot"),
                quantity(rs.getBigDecimal("planned_qty")), rs.getBigDecimal("produced_qty") == null ? null : quantity(rs.getBigDecimal("produced_qty")),
                rs.getString("status"), null, List.of(), finishedAt == null ? null : finishedAt.toInstant(),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static ProductionEventResponse eventResponse(ResultSet rs) throws SQLException {
        return new ProductionEventResponse(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getObject("actor_user_id", UUID.class),
                rs.getString("reason"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    public void event(UUID productionOrderId, String type, UUID actor, String reason) {
        jdbc.sql("INSERT INTO production.production_event (id, production_order_id, event_type, actor_user_id, reason) VALUES (:id, :orderId, :type, :actor, :reason)")
                .param("id", UUID.randomUUID()).param("orderId", productionOrderId).param("type", type).param("actor", actor).param("reason", reason).update();
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static String nullableQuantity(BigDecimal value) {
        return value == null ? null : quantity(value);
    }

    private static String quantity(BigDecimal value) { return value.setScale(4).toPlainString(); }
    public record GroupRow(UUID id, long version, String groupNo, UUID buyerOrderId) { }
    private record EventWithOrder(UUID orderId, ProductionEventResponse event) { }
    private record ConfigWithOrder(UUID orderId, ProductionConfigurationResponse configuration) { }
    private record ProcessWithOrder(UUID orderId, ProductionProcessResponse process) { }
    private record YarnWithOrder(UUID orderId, ProductionYarnLineResponse yarnLine) { }
}
