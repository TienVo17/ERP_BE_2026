package com.company.erp.masterdata.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.company.erp.identity.application.AdminQuery;
import com.company.erp.masterdata.api.MasterDataModels.CurrencyResponse;
import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateRequest;
import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateResponse;
import com.company.erp.masterdata.api.MasterDataModels.UomRequest;
import com.company.erp.masterdata.api.MasterDataModels.UomResponse;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReferenceMasterJdbcRepository {

    private static final Map<String, String> CURRENCY_SORT = Map.of(
            "code", "code", "name", "lower(name)");
    private static final Map<String, String> UOM_SORT = Map.of(
            "code", "upper(btrim(code))", "name", "lower(name)", "id", "id");
    private static final Map<String, String> RATE_SORT = Map.of(
            "month", "effective_month", "id", "id");

    private final JdbcClient jdbc;

    public ReferenceMasterJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> currencySort(List<String> sort) {
        List<String> normalized = new java.util.ArrayList<>(AdminQuery.sort(sort, CURRENCY_SORT, List.of("code,asc")));
        if (normalized.stream().noneMatch(term -> term.startsWith("code,"))) {
            normalized.add("code,asc");
        }
        return List.copyOf(normalized);
    }

    public List<CurrencyResponse> currencies(int page, int size, Boolean active, List<String> sort) {
        return jdbc.sql("""
                        SELECT code, name, active FROM master_data.currency
                        WHERE (CAST(:active AS boolean) IS NULL OR active = :active)
                        ORDER BY %s LIMIT :limit OFFSET :offset
                        """.formatted(AdminQuery.orderBy(sort, CURRENCY_SORT)))
                .param("active", active).param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> new CurrencyResponse(rs.getString("code"), rs.getString("name"), rs.getBoolean("active")))
                .list();
    }

    public long countCurrencies(Boolean active) {
        return jdbc.sql("SELECT count(*) FROM master_data.currency WHERE (CAST(:active AS boolean) IS NULL OR active = :active)")
                .param("active", active).query(Long.class).single();
    }

    public List<String> uomSort(List<String> sort) {
        return AdminQuery.sort(sort, UOM_SORT, List.of("code,asc", "id,asc"));
    }

    public List<UomResponse> uoms(int page, int size, String status, String code, List<String> sort) {
        return jdbc.sql("""
                        SELECT * FROM master_data.uom
                        WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
                          AND (CAST(:code AS varchar) IS NULL OR upper(btrim(code)) = :code)
                        ORDER BY %s LIMIT :limit OFFSET :offset
                        """.formatted(AdminQuery.orderBy(sort, UOM_SORT)))
                .param("status", status).param("code", code).param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> uom(rs)).list();
    }

    public long countUoms(String status, String code) {
        return jdbc.sql("""
                        SELECT count(*) FROM master_data.uom
                        WHERE (CAST(:status AS varchar) IS NULL OR status = :status)
                          AND (CAST(:code AS varchar) IS NULL OR upper(btrim(code)) = :code)
                        """).param("status", status).param("code", code).query(Long.class).single();
    }

    public Optional<UomResponse> uom(UUID id) {
        return jdbc.sql("SELECT * FROM master_data.uom WHERE id=:id").param("id", id)
                .query((rs, row) -> uom(rs)).optional();
    }

    public boolean uomCodeExists(String code, UUID excludedId) {
        return jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM master_data.uom
                        WHERE upper(btrim(code))=:code
                          AND (CAST(:excludedId AS uuid) IS NULL OR id<>:excludedId))
                        """).param("code", code).param("excludedId", excludedId).query(Boolean.class).single();
    }

    public UUID createUom(UomRequest request, UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.uom (id, code, name, created_by, updated_by)
                        VALUES (:id, :code, :name, :actor, :actor)
                        """).param("id", id).param("code", request.code()).param("name", request.name())
                .param("actor", actor).update();
        return id;
    }

    public int updateUom(UUID id, long version, UomRequest request, UUID actor) {
        return jdbc.sql("""
                        UPDATE master_data.uom SET code=:code, name=:name, version=version+1, updated_by=:actor
                        WHERE id=:id AND version=:version
                        """).param("code", request.code()).param("name", request.name()).param("actor", actor)
                .param("id", id).param("version", version).update();
    }

    public int archiveUom(UUID id, long version, UUID actor) {
        return jdbc.sql("""
                        UPDATE master_data.uom SET status='ARCHIVED', version=version+1, updated_by=:actor
                        WHERE id=:id AND version=:version
                        """).param("actor", actor).param("id", id).param("version", version).update();
    }

    public List<String> rateSort(List<String> sort) {
        return AdminQuery.sort(sort, RATE_SORT, List.of("month,desc", "id,asc"));
    }

    public List<ExchangeRateResponse> rates(int page, int size, LocalDate month, String status, List<String> sort) {
        return jdbc.sql("""
                        SELECT * FROM master_data.monthly_exchange_rate
                        WHERE (CAST(:month AS date) IS NULL OR effective_month=:month)
                          AND (CAST(:status AS varchar) IS NULL OR status=:status)
                        ORDER BY %s LIMIT :limit OFFSET :offset
                        """.formatted(AdminQuery.orderBy(sort, RATE_SORT)))
                .param("month", month).param("status", status).param("limit", size)
                .param("offset", Math.multiplyExact((long) page, size))
                .query((rs, row) -> rate(rs)).list();
    }

    public long countRates(LocalDate month, String status) {
        return jdbc.sql("""
                        SELECT count(*) FROM master_data.monthly_exchange_rate
                        WHERE (CAST(:month AS date) IS NULL OR effective_month=:month)
                          AND (CAST(:status AS varchar) IS NULL OR status=:status)
                        """).param("month", month).param("status", status).query(Long.class).single();
    }

    public Optional<ExchangeRateResponse> rate(UUID id) {
        return jdbc.sql("SELECT * FROM master_data.monthly_exchange_rate WHERE id=:id").param("id", id)
                .query((rs, row) -> rate(rs)).optional();
    }

    public boolean rateMonthExists(LocalDate month, UUID excludedId) {
        return jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM master_data.monthly_exchange_rate
                        WHERE effective_month=:month
                          AND (CAST(:excludedId AS uuid) IS NULL OR id<>:excludedId))
                        """).param("month", month).param("excludedId", excludedId).query(Boolean.class).single();
    }

    public UUID createRate(LocalDate month, ExchangeRateRequest request, UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO master_data.monthly_exchange_rate
                        (id,effective_month,vnd_usd_rate,won_usd_rate,source,created_by,updated_by)
                        VALUES (:id,:month,:vnd,:won,:source,:actor,:actor)
                        """).param("id", id).param("month", month)
                .param("vnd", new BigDecimal(request.vndUsdRate())).param("won", new BigDecimal(request.wonUsdRate()))
                .param("source", request.source()).param("actor", actor).update();
        return id;
    }

    public int updateRate(UUID id, long version, LocalDate month, ExchangeRateRequest request, UUID actor) {
        return jdbc.sql("""
                        UPDATE master_data.monthly_exchange_rate
                        SET effective_month=:month,vnd_usd_rate=:vnd,won_usd_rate=:won,source=:source,
                            version=version+1,updated_by=:actor
                        WHERE id=:id AND version=:version
                        """).param("month", month).param("vnd", new BigDecimal(request.vndUsdRate()))
                .param("won", new BigDecimal(request.wonUsdRate())).param("source", request.source())
                .param("actor", actor).param("id", id).param("version", version).update();
    }

    public int archiveRate(UUID id, long version, UUID actor) {
        return jdbc.sql("""
                        UPDATE master_data.monthly_exchange_rate SET status='ARCHIVED',version=version+1,updated_by=:actor
                        WHERE id=:id AND version=:version
                        """).param("actor", actor).param("id", id).param("version", version).update();
    }

    public boolean postedDeliveryUsesRate(UUID id) {
        return jdbc.sql("""
                        SELECT EXISTS(SELECT 1 FROM delivery.delivery_note
                        WHERE exchange_rate_id=:id AND status IN ('POSTED','REVERSED'))
                        """).param("id", id).query(Boolean.class).single();
    }

    private static UomResponse uom(ResultSet rs) throws SQLException {
        return new UomResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status"),
                rs.getString("code"), rs.getString("name"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static ExchangeRateResponse rate(ResultSet rs) throws SQLException {
        return new ExchangeRateResponse(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status"),
                rs.getObject("effective_month", LocalDate.class).toString().substring(0, 7),
                rs.getBigDecimal("vnd_usd_rate").setScale(6).toPlainString(),
                rs.getBigDecimal("won_usd_rate").setScale(6).toPlainString(), rs.getString("source"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
