package com.company.erp.masterdata.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.masterdata.api.MasterDataModels.CurrencyResponse;
import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateRequest;
import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateResponse;
import com.company.erp.masterdata.api.MasterDataModels.PageResponse;
import com.company.erp.masterdata.api.MasterDataModels.UomRequest;
import com.company.erp.masterdata.api.MasterDataModels.UomResponse;
import com.company.erp.masterdata.infrastructure.ReferenceMasterJdbcRepository;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferenceMasterService {

    private final ReferenceMasterJdbcRepository repository;
    private final AuditEventWriter auditWriter;

    public ReferenceMasterService(ReferenceMasterJdbcRepository repository, AuditEventWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    public PageResponse<CurrencyResponse> currencies(int page, int size, List<String> requestedSort, Boolean active) {
        AdminQuery.validatePage(page, size);
        List<String> sort = repository.currencySort(requestedSort);
        return MasterDataSupport.page(repository.currencies(page, size, active, sort), page, size,
                repository.countCurrencies(active), AdminQuery.filters("active", active), sort);
    }

    public PageResponse<UomResponse> uoms(int page, int size, List<String> requestedSort, String status, String code) {
        AdminQuery.validatePage(page, size);
        String normalizedStatus = MasterDataSupport.status(status);
        String normalizedCode = code == null || code.isBlank() ? null : MasterDataSupport.canonicalCode(code);
        List<String> sort = repository.uomSort(requestedSort);
        return MasterDataSupport.page(repository.uoms(page, size, normalizedStatus, normalizedCode, sort), page, size,
                repository.countUoms(normalizedStatus, normalizedCode),
                AdminQuery.filters("status", normalizedStatus, "code", normalizedCode), sort);
    }

    public UomResponse uom(UUID id) {
        return repository.uom(id).orElseThrow(() -> new ResourceNotFoundException("uom", id.toString()));
    }

    @Transactional
    public UomResponse createUom(UomRequest request, ErpPrincipal actor, String requestId) {
        UomRequest normalized = new UomRequest(MasterDataSupport.canonicalCode(request.code()),
                MasterDataSupport.optionalText(request.name()));
        if (repository.uomCodeExists(normalized.code(), null)) {
            duplicate();
        }
        UUID id = repository.createUom(normalized, actor.user().id());
        UomResponse created = uom(id);
        auditWriter.write(actor.user().id(), "CREATE", "UOM", id, requestId, null, null, summary(created));
        return created;
    }

    @Transactional
    public UomResponse updateUom(UUID id, long version, UomRequest request, ErpPrincipal actor, String requestId) {
        UomResponse before = uom(id);
        UomRequest normalized = new UomRequest(MasterDataSupport.canonicalCode(request.code()),
                MasterDataSupport.optionalText(request.name()));
        if (repository.uomCodeExists(normalized.code(), id)) {
            duplicate();
        }
        if (repository.updateUom(id, version, normalized, actor.user().id()) != 1) {
            MasterDataSupport.staleOrMissing(repository.uom(id).isPresent());
        }
        UomResponse after = uom(id);
        auditWriter.write(actor.user().id(), "UPDATE", "UOM", id, requestId, null, summary(before), summary(after));
        return after;
    }

    @Transactional
    public UomResponse archiveUom(UUID id, long version, ErpPrincipal actor, String requestId) {
        UomResponse before = uom(id);
        if (repository.archiveUom(id, version, actor.user().id()) != 1) {
            MasterDataSupport.staleOrMissing(repository.uom(id).isPresent());
        }
        UomResponse after = uom(id);
        auditWriter.write(actor.user().id(), "ARCHIVE", "UOM", id, requestId, null, summary(before), summary(after));
        return after;
    }

    public PageResponse<ExchangeRateResponse> rates(
            int page, int size, List<String> requestedSort, String month, String status) {
        AdminQuery.validatePage(page, size);
        LocalDate normalizedMonth = month == null || month.isBlank() ? null : parseMonth(month);
        String normalizedStatus = MasterDataSupport.status(status);
        List<String> sort = repository.rateSort(requestedSort);
        return MasterDataSupport.page(repository.rates(page, size, normalizedMonth, normalizedStatus, sort), page, size,
                repository.countRates(normalizedMonth, normalizedStatus),
                AdminQuery.filters("month", month, "status", normalizedStatus), sort);
    }

    public ExchangeRateResponse rate(UUID id) {
        return repository.rate(id).orElseThrow(() -> new ResourceNotFoundException("exchange rate", id.toString()));
    }

    @Transactional
    public ExchangeRateResponse createRate(ExchangeRateRequest request, ErpPrincipal actor, String requestId) {
        ExchangeRateRequest normalized = normalized(request);
        LocalDate month = parseMonth(normalized.month());
        if (repository.rateMonthExists(month, null)) {
            duplicate();
        }
        UUID id = repository.createRate(month, normalized, actor.user().id());
        ExchangeRateResponse created = rate(id);
        auditWriter.write(actor.user().id(), "CREATE", "EXCHANGE_RATE", id, requestId, null, null, summary(created));
        return created;
    }

    @Transactional
    public ExchangeRateResponse updateRate(
            UUID id, long version, ExchangeRateRequest request, ErpPrincipal actor, String requestId) {
        try {
            ExchangeRateResponse before = rate(id);
            if (repository.postedDeliveryUsesRate(id)) {
                MasterDataSupport.inUse("Exchange rate");
            }
            ExchangeRateRequest normalized = normalized(request);
            LocalDate month = parseMonth(normalized.month());
            if (repository.rateMonthExists(month, id)) {
                duplicate();
            }
            if (repository.updateRate(id, version, month, normalized, actor.user().id()) != 1) {
                MasterDataSupport.staleOrMissing(repository.rate(id).isPresent());
            }
            ExchangeRateResponse after = rate(id);
            auditWriter.write(actor.user().id(), "UPDATE", "EXCHANGE_RATE", id, requestId, null, summary(before), summary(after));
            return after;
        } catch (DataAccessException exception) {
            throw MasterDataSupport.mapUsageRace(exception, "Exchange rate");
        }
    }

    @Transactional
    public ExchangeRateResponse archiveRate(UUID id, long version, ErpPrincipal actor, String requestId) {
        try {
            ExchangeRateResponse before = rate(id);
            if (repository.postedDeliveryUsesRate(id)) {
                MasterDataSupport.inUse("Exchange rate");
            }
            if (repository.archiveRate(id, version, actor.user().id()) != 1) {
                MasterDataSupport.staleOrMissing(repository.rate(id).isPresent());
            }
            ExchangeRateResponse after = rate(id);
            auditWriter.write(actor.user().id(), "ARCHIVE", "EXCHANGE_RATE", id, requestId, null, summary(before), summary(after));
            return after;
        } catch (DataAccessException exception) {
            throw MasterDataSupport.mapUsageRace(exception, "Exchange rate");
        }
    }

    private static ExchangeRateRequest normalized(ExchangeRateRequest request) {
        return new ExchangeRateRequest(request.month(), request.vndUsdRate(), request.wonUsdRate(),
                MasterDataSupport.optionalText(request.source()));
    }

    private static LocalDate parseMonth(String value) {
        try {
            return YearMonth.parse(value).atDay(1);
        } catch (RuntimeException exception) {
            throw new com.company.erp.api.ApiException(com.company.erp.api.ApiErrorCode.VALIDATION_FAILED,
                    "month must use YYYY-MM format.");
        }
    }

    private static void duplicate() {
        throw new com.company.erp.api.ApiException(com.company.erp.api.ApiErrorCode.DUPLICATE_BUSINESS_KEY,
                "A resource with the same business key already exists.");
    }

    private static Map<String, Object> summary(UomResponse value) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", value.id());
        data.put("version", value.version());
        data.put("status", value.status());
        data.put("code", value.code());
        data.put("name", value.name());
        return data;
    }

    private static Map<String, Object> summary(ExchangeRateResponse value) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", value.id());
        data.put("version", value.version());
        data.put("status", value.status());
        data.put("month", value.month());
        data.put("vndUsdRate", value.vndUsdRate());
        data.put("wonUsdRate", value.wonUsdRate());
        data.put("source", value.source());
        return data;
    }
}
