package com.company.erp.delivery.infrastructure;

import com.company.erp.identity.application.AdminQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One query serves both the JSON page and the XLSX export. Keeping a single SQL builder is what
 * stops the two surfaces from drifting into different row sets for the same filters.
 */
@Repository
public class DebitProjectionJdbcRepository {

    public static final Map<String, String> SORT = Map.of(
            "deliveryDate", "delivery_date",
            "deliveryNo", "delivery_no",
            "lineNo", "line_no",
            "deliveryNoteItemId", "delivery_note_item_id");
    private static final String FROM = """
            FROM delivery.debit_note_projection
            WHERE (CAST(:customerId AS uuid) IS NULL OR customer_id = :customerId)
              AND (CAST(:deliveryNo AS varchar) IS NULL OR upper(delivery_no) LIKE upper(:deliveryNo))
              AND (CAST(:sysPoNo AS varchar) IS NULL OR upper(sys_po_no_snapshot) LIKE upper(:sysPoNo))
              AND (CAST(:buyerPo AS varchar) IS NULL OR upper(buyer_po_snapshot) LIKE upper(:buyerPo))
              AND (CAST(:dateFrom AS date) IS NULL OR delivery_date >= :dateFrom)
              AND (CAST(:dateTo AS date) IS NULL OR delivery_date <= :dateTo)
              AND (CAST(:currencyCode AS varchar) IS NULL OR currency_code = :currencyCode)
            """;

    private final JdbcClient jdbc;

    public DebitProjectionJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> sort(List<String> requested) {
        return AdminQuery.sort(requested, SORT,
                List.of("deliveryDate,desc", "deliveryNo,asc", "lineNo,asc"));
    }

    public long count(Filters filters) {
        return bind(jdbc.sql("SELECT count(*) " + FROM), filters).query(Long.class).single();
    }

    public List<DebitRow> rows(Filters filters, List<String> sort, Integer limit, Long offset) {
        String sql = "SELECT * " + FROM + " ORDER BY " + AdminQuery.orderBy(sort, SORT)
                + (limit == null ? "" : " LIMIT :limit OFFSET :offset");
        var statement = bind(jdbc.sql(sql), filters);
        if (limit != null) {
            statement = statement.param("limit", limit).param("offset", offset);
        }
        return statement.query((rs, row) -> new DebitRow(
                        rs.getObject("delivery_note_item_id", UUID.class),
                        rs.getString("debit_reference"),
                        rs.getObject("delivery_note_id", UUID.class),
                        rs.getString("delivery_no"),
                        rs.getDate("delivery_date").toLocalDate(),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("customer_name_snapshot"),
                        rs.getInt("line_no"),
                        rs.getString("sys_po_no_snapshot"),
                        rs.getString("buyer_po_snapshot"),
                        rs.getString("product_kind_snapshot"),
                        rs.getString("style_no_snapshot"),
                        rs.getString("name_snapshot"),
                        rs.getString("size_snapshot"),
                        rs.getString("color_snapshot"),
                        rs.getString("uom_code_snapshot"),
                        rs.getBigDecimal("total_qty"),
                        rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency_code")))
                .list();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Filters filters) {
        return statement
                .param("customerId", filters.customerId())
                .param("deliveryNo", like(filters.deliveryNo()))
                .param("sysPoNo", like(filters.sysPoNo()))
                .param("buyerPo", like(filters.buyerPo()))
                .param("dateFrom", filters.deliveryDateFrom())
                .param("dateTo", filters.deliveryDateTo())
                .param("currencyCode", filters.currencyCode());
    }

    private static String like(String value) {
        return value == null || value.isBlank() ? null : "%" + value.trim() + "%";
    }

    public record Filters(
            UUID customerId, String deliveryNo, String sysPoNo, String buyerPo,
            LocalDate deliveryDateFrom, LocalDate deliveryDateTo, String currencyCode) { }

    public record DebitRow(
            UUID deliveryNoteItemId, String debitReference, UUID deliveryNoteId, String deliveryNo,
            LocalDate deliveryDate, UUID customerId, String customerName, int lineNo, String sysPoNo,
            String buyerPo, String productKind, String styleNo, String name, String size, String color,
            String uomCode, BigDecimal totalQty, BigDecimal unitPrice, BigDecimal amount,
            String currencyCode) { }
}
