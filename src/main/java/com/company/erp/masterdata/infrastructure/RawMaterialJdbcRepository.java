package com.company.erp.masterdata.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.application.AdminQuery;
import com.company.erp.masterdata.api.MasterDataModels.RawMaterialRequest;
import com.company.erp.masterdata.api.MasterDataModels.RawMaterialResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RawMaterialJdbcRepository {

    private static final Map<String, String> SORT = Map.of(
            "code", "upper(btrim(rm.code))", "name", "lower(rm.name)", "id", "rm.id");
    private static final String SELECT = """
            SELECT rm.*, u.code AS uom_code, s.name AS supplier_name
            FROM master_data.raw_material rm
            JOIN master_data.uom u ON u.id = rm.uom_id
            LEFT JOIN master_data.supplier s ON s.id = rm.supplier_id
            """;
    private static final String FILTER = """
            WHERE (CAST(:status AS varchar) IS NULL OR rm.status = :status)
              AND (CAST(:code AS varchar) IS NULL OR upper(btrim(rm.code)) = :code)
              AND (CAST(:name AS varchar) IS NULL OR lower(rm.name) LIKE lower(:name))
              AND (CAST(:supplierId AS uuid) IS NULL OR rm.supplier_id = :supplierId)
            """;

    private final JdbcClient jdbc;

    public RawMaterialJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> sort(List<String> requested) {
        return AdminQuery.sort(requested, SORT, List.of("code,asc", "id,asc"));
    }

    public List<RawMaterialResponse> list(
            int page, int size, String status, String code, String name, UUID supplierId, List<String> sort) {
        return filtered(jdbc.sql(SELECT + FILTER + "ORDER BY " + AdminQuery.orderBy(sort, SORT)
                        + " LIMIT :limit OFFSET :offset"), status, code, name, supplierId)
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> map(rs))
                .list();
    }

    public long count(String status, String code, String name, UUID supplierId) {
        return filtered(jdbc.sql("SELECT count(*) FROM master_data.raw_material rm " + FILTER),
                status, code, name, supplierId)
                .query(Long.class)
                .single();
    }

    public Optional<RawMaterialResponse> find(UUID id) {
        return jdbc.sql(SELECT + "WHERE rm.id = :id").param("id", id).query((rs, row) -> map(rs)).optional();
    }

    public boolean codeExists(String code, UUID excludedId) {
        return jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM master_data.raw_material
                        WHERE upper(btrim(code)) = :code
                          AND (CAST(:excludedId AS uuid) IS NULL OR id <> :excludedId))
                        """)
                .param("code", code).param("excludedId", excludedId).query(Boolean.class).single();
    }

    public Optional<String> uomStatus(UUID id) {
        return jdbc.sql("SELECT status FROM master_data.uom WHERE id = :id")
                .param("id", id).query(String.class).optional();
    }

    public Optional<String> supplierStatus(UUID id) {
        return jdbc.sql("SELECT status FROM master_data.supplier WHERE id = :id")
                .param("id", id).query(String.class).optional();
    }

    public Optional<Boolean> currencyActive(String code) {
        return jdbc.sql("SELECT active FROM master_data.currency WHERE code = :code")
                .param("code", code).query(Boolean.class).optional();
    }

    public UUID create(RawMaterialRequest request, UUID actor) {
        UUID id = UUID.randomUUID();
        write(jdbc.sql("""
                        INSERT INTO master_data.raw_material (
                            id, category, code, name, specification, size, color, uom_id,
                            reference_price, currency_code, supplier_id, safety_stock_qty, remark,
                            created_by, updated_by
                        ) VALUES (
                            :id, :category, :code, :name, :specification, :size, :color, :uomId,
                            :referencePrice, :currencyCode, :supplierId, :safetyStockQty, :remark,
                            :actor, :actor
                        )
                        """), request)
                .param("id", id).param("actor", actor).update();
        return id;
    }

    public int update(UUID id, long version, RawMaterialRequest request, UUID actor) {
        return write(jdbc.sql("""
                        UPDATE master_data.raw_material SET
                            category = :category, code = :code, name = :name, specification = :specification,
                            size = :size, color = :color, uom_id = :uomId, reference_price = :referencePrice,
                            currency_code = :currencyCode, supplier_id = :supplierId,
                            safety_stock_qty = :safetyStockQty, remark = :remark,
                            version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version
                        """), request)
                .param("actor", actor).param("id", id).param("version", version).update();
    }

    public int archive(UUID id, long version, UUID actor) {
        return jdbc.sql("""
                        UPDATE master_data.raw_material SET status = 'ARCHIVED', version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version
                        """).param("actor", actor).param("id", id).param("version", version).update();
    }

    private static JdbcClient.StatementSpec filtered(
            JdbcClient.StatementSpec statement, String status, String code, String name, UUID supplierId) {
        return statement
                .param("status", status)
                .param("code", code)
                .param("name", name == null ? null : "%" + name + "%")
                .param("supplierId", supplierId);
    }

    private static JdbcClient.StatementSpec write(JdbcClient.StatementSpec statement, RawMaterialRequest request) {
        return statement
                .param("category", request.category())
                .param("code", request.code())
                .param("name", request.name())
                .param("specification", request.specification())
                .param("size", request.size())
                .param("color", request.color())
                .param("uomId", request.uomId())
                .param("referencePrice", decimal(request.referencePrice()))
                .param("currencyCode", request.currencyCode())
                .param("supplierId", request.supplierId())
                .param("safetyStockQty", decimal(request.safetyStockQty()))
                .param("remark", request.remark());
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static RawMaterialResponse map(ResultSet rs) throws SQLException {
        return new RawMaterialResponse(
                rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status"),
                rs.getString("category"), rs.getString("code"), rs.getString("name"),
                rs.getString("specification"), rs.getString("size"), rs.getString("color"),
                rs.getObject("uom_id", UUID.class), rs.getString("uom_code"),
                scaled(rs.getBigDecimal("reference_price"), 6), rs.getString("currency_code"),
                rs.getObject("supplier_id", UUID.class), rs.getString("supplier_name"),
                scaled(rs.getBigDecimal("safety_stock_qty"), 4), rs.getString("remark"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static String scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale).toPlainString();
    }
}
