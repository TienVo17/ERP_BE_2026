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
import com.company.erp.masterdata.api.MasterDataModels.FinishedGoodRequest;
import com.company.erp.masterdata.api.MasterDataModels.FinishedGoodResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FinishedGoodJdbcRepository {

    private static final Map<String, String> SORT = Map.of(
            "styleNo", "upper(btrim(fg.style_no))", "name", "lower(fg.name)", "id", "fg.id");
    private static final String SELECT = """
            SELECT fg.*, u.code AS uom_code
            FROM master_data.finished_good fg
            JOIN master_data.uom u ON u.id = fg.uom_id
            """;
    private static final String FILTER = """
            WHERE (CAST(:status AS varchar) IS NULL OR fg.status = :status)
              AND (CAST(:productKind AS varchar) IS NULL OR fg.product_kind = :productKind)
              AND (CAST(:styleNo AS varchar) IS NULL OR upper(btrim(fg.style_no)) = :styleNo)
              AND (CAST(:name AS varchar) IS NULL OR lower(fg.name) LIKE lower(:name))
            """;

    private final JdbcClient jdbc;

    public FinishedGoodJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> sort(List<String> requested) {
        return AdminQuery.sort(requested, SORT, List.of("styleNo,asc", "id,asc"));
    }

    public List<FinishedGoodResponse> list(
            int page, int size, String status, String productKind, String styleNo, String name, List<String> sort) {
        return filtered(jdbc.sql(SELECT + FILTER + "ORDER BY " + AdminQuery.orderBy(sort, SORT)
                        + " LIMIT :limit OFFSET :offset"), status, productKind, styleNo, name)
                .param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> map(rs))
                .list();
    }

    public long count(String status, String productKind, String styleNo, String name) {
        return filtered(jdbc.sql("SELECT count(*) FROM master_data.finished_good fg " + FILTER),
                status, productKind, styleNo, name)
                .query(Long.class)
                .single();
    }

    public Optional<FinishedGoodResponse> find(UUID id) {
        return jdbc.sql(SELECT + "WHERE fg.id = :id").param("id", id).query((rs, row) -> map(rs)).optional();
    }

    /** Mirrors the V003 canonical composite unique index, including its blank-as-null normalization. */
    public boolean keyExists(FinishedGoodRequest request, UUID excludedId) {
        return jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM master_data.finished_good
                        WHERE product_kind = :productKind
                          AND upper(btrim(style_no)) = upper(btrim(:styleNo))
                          AND upper(btrim(name)) = upper(btrim(:name))
                          AND upper(COALESCE(NULLIF(btrim(size), ''), '<NULL>'))
                              = upper(COALESCE(NULLIF(btrim(CAST(:size AS varchar)), ''), '<NULL>'))
                          AND upper(COALESCE(NULLIF(btrim(color), ''), '<NULL>'))
                              = upper(COALESCE(NULLIF(btrim(CAST(:color AS varchar)), ''), '<NULL>'))
                          AND (CAST(:excludedId AS uuid) IS NULL OR id <> :excludedId))
                        """)
                .param("productKind", request.productKind())
                .param("styleNo", request.styleNo())
                .param("name", request.name())
                .param("size", request.size())
                .param("color", request.color())
                .param("excludedId", excludedId)
                .query(Boolean.class)
                .single();
    }

    public boolean used(UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM sales.buyer_order_item WHERE finished_good_id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    public Optional<String> uomStatus(UUID id) {
        return jdbc.sql("SELECT status FROM master_data.uom WHERE id = :id")
                .param("id", id).query(String.class).optional();
    }

    public Optional<Boolean> currencyActive(String code) {
        return jdbc.sql("SELECT active FROM master_data.currency WHERE code = :code")
                .param("code", code).query(Boolean.class).optional();
    }

    public UUID create(FinishedGoodRequest request, UUID actor) {
        UUID id = UUID.randomUUID();
        write(jdbc.sql("""
                        INSERT INTO master_data.finished_good (
                            id, product_kind, style_no, name, size, color, uom_id,
                            reference_price, currency_code, created_by, updated_by
                        ) VALUES (
                            :id, :productKind, :styleNo, :name, :size, :color, :uomId,
                            :referencePrice, :currencyCode, :actor, :actor
                        )
                        """), request)
                .param("id", id).param("actor", actor).update();
        return id;
    }

    public int update(UUID id, long version, FinishedGoodRequest request, UUID actor) {
        return write(jdbc.sql("""
                        UPDATE master_data.finished_good SET
                            product_kind = :productKind, style_no = :styleNo, name = :name, size = :size,
                            color = :color, uom_id = :uomId, reference_price = :referencePrice,
                            currency_code = :currencyCode, version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version
                        """), request)
                .param("actor", actor).param("id", id).param("version", version).update();
    }

    public int archive(UUID id, long version, UUID actor) {
        return jdbc.sql("""
                        UPDATE master_data.finished_good SET status = 'ARCHIVED', version = version + 1, updated_by = :actor
                        WHERE id = :id AND version = :version
                        """).param("actor", actor).param("id", id).param("version", version).update();
    }

    private static JdbcClient.StatementSpec filtered(
            JdbcClient.StatementSpec statement, String status, String productKind, String styleNo, String name) {
        return statement
                .param("status", status)
                .param("productKind", productKind)
                .param("styleNo", styleNo)
                .param("name", name == null ? null : "%" + name + "%");
    }

    private static JdbcClient.StatementSpec write(JdbcClient.StatementSpec statement, FinishedGoodRequest request) {
        return statement
                .param("productKind", request.productKind())
                .param("styleNo", request.styleNo())
                .param("name", request.name())
                .param("size", request.size())
                .param("color", request.color())
                .param("uomId", request.uomId())
                .param("referencePrice", request.referencePrice() == null ? null : new BigDecimal(request.referencePrice()))
                .param("currencyCode", request.currencyCode());
    }

    private static FinishedGoodResponse map(ResultSet rs) throws SQLException {
        return new FinishedGoodResponse(
                rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status"),
                rs.getString("product_kind"), rs.getString("style_no"), rs.getString("name"),
                rs.getString("size"), rs.getString("color"), rs.getObject("uom_id", UUID.class),
                rs.getString("uom_code"), scaled(rs.getBigDecimal("reference_price")),
                rs.getString("currency_code"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static String scaled(BigDecimal value) {
        return value == null ? null : value.setScale(6).toPlainString();
    }
}
