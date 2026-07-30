package com.company.erp.masterdata.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.masterdata.api.MasterDataModels.FinishedGoodRequest;
import com.company.erp.masterdata.api.MasterDataModels.FinishedGoodResponse;
import com.company.erp.masterdata.api.MasterDataModels.PageResponse;
import com.company.erp.masterdata.infrastructure.FinishedGoodJdbcRepository;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Media stays deferred: no image field is accepted and {@code image_asset_id} remains null.
 * Usage is derived from the Buyer Order Item relationship, never from a request flag.
 */
@Service
public class FinishedGoodService {

    private final FinishedGoodJdbcRepository repository;
    private final AuditEventWriter auditWriter;

    public FinishedGoodService(FinishedGoodJdbcRepository repository, AuditEventWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    public PageResponse<FinishedGoodResponse> list(
            int page, int size, List<String> requestedSort, String status, String productKind,
            String styleNo, String name) {
        AdminQuery.validatePage(page, size);
        String normalizedStatus = MasterDataSupport.status(status);
        String normalizedKind = AdminQuery.enumValue(productKind, java.util.Set.of("PRINT", "WOVEN"), "productKind");
        String normalizedStyle = styleNo == null || styleNo.isBlank() ? null : MasterDataSupport.canonicalCode(styleNo);
        String normalizedName = MasterDataSupport.optionalText(name);
        List<String> sort = repository.sort(requestedSort);
        return MasterDataSupport.page(
                repository.list(page, size, normalizedStatus, normalizedKind, normalizedStyle, normalizedName, sort),
                page, size,
                repository.count(normalizedStatus, normalizedKind, normalizedStyle, normalizedName),
                AdminQuery.filters("status", normalizedStatus, "productKind", normalizedKind,
                        "styleNo", normalizedStyle, "name", normalizedName),
                sort);
    }

    public FinishedGoodResponse get(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResourceNotFoundException("finished good", id.toString()));
    }

    @Transactional
    public FinishedGoodResponse create(FinishedGoodRequest request, ErpPrincipal actor, String requestId) {
        FinishedGoodRequest normalized = normalized(request);
        validateReferences(normalized, null);
        if (repository.keyExists(normalized, null)) {
            duplicate();
        }
        UUID id = repository.create(normalized, actor.user().id());
        FinishedGoodResponse created = get(id);
        auditWriter.write(actor.user().id(), "CREATE", "FINISHED_GOOD", id, requestId, null, null, summary(created));
        return created;
    }

    @Transactional
    public FinishedGoodResponse update(
            UUID id, long version, FinishedGoodRequest request, ErpPrincipal actor, String requestId) {
        try {
            FinishedGoodResponse before = get(id);
            if (repository.used(id)) {
                MasterDataSupport.inUse("Finished good");
            }
            FinishedGoodRequest normalized = normalized(request);
            validateReferences(normalized, before);
            if (repository.keyExists(normalized, id)) {
                duplicate();
            }
            if (repository.update(id, version, normalized, actor.user().id()) != 1) {
                MasterDataSupport.staleOrMissing(repository.find(id).isPresent());
            }
            FinishedGoodResponse after = get(id);
            auditWriter.write(actor.user().id(), "UPDATE", "FINISHED_GOOD", id, requestId, null,
                    summary(before), summary(after));
            return after;
        } catch (DataAccessException exception) {
            throw MasterDataSupport.mapUsageRace(exception, "Finished good");
        }
    }

    @Transactional
    public FinishedGoodResponse archive(UUID id, long version, ErpPrincipal actor, String requestId) {
        try {
            FinishedGoodResponse before = get(id);
            if (repository.used(id)) {
                MasterDataSupport.inUse("Finished good");
            }
            if (repository.archive(id, version, actor.user().id()) != 1) {
                MasterDataSupport.staleOrMissing(repository.find(id).isPresent());
            }
            FinishedGoodResponse after = get(id);
            auditWriter.write(actor.user().id(), "ARCHIVE", "FINISHED_GOOD", id, requestId, null,
                    summary(before), summary(after));
            return after;
        } catch (DataAccessException exception) {
            throw MasterDataSupport.mapUsageRace(exception, "Finished good");
        }
    }

    private static FinishedGoodRequest normalized(FinishedGoodRequest request) {
        if ((request.referencePrice() == null) != (request.currencyCode() == null)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                    "referencePrice and currencyCode must be supplied together or omitted together.");
        }
        return new FinishedGoodRequest(
                request.productKind(),
                MasterDataSupport.requiredText(request.styleNo()),
                MasterDataSupport.requiredText(request.name()),
                MasterDataSupport.optionalText(request.size()),
                MasterDataSupport.optionalText(request.color()),
                request.uomId(),
                request.referencePrice(),
                request.currencyCode() == null ? null : MasterDataSupport.canonicalCode(request.currencyCode()));
    }

    private void validateReferences(FinishedGoodRequest request, FinishedGoodResponse before) {
        if (before == null || !request.uomId().equals(before.uomId())) {
            MasterDataSupport.requireActive(repository.uomStatus(request.uomId()), "uomId");
        }
        if (request.currencyCode() != null
                && (before == null || !request.currencyCode().equals(before.currencyCode()))) {
            MasterDataSupport.requireActiveCurrency(repository.currencyActive(request.currencyCode()));
        }
    }

    private static void duplicate() {
        throw new ApiException(ApiErrorCode.DUPLICATE_BUSINESS_KEY,
                "A resource with the same business key already exists.");
    }

    private static Map<String, Object> summary(FinishedGoodResponse value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", value.id());
        data.put("version", value.version());
        data.put("status", value.status());
        data.put("productKind", value.productKind());
        data.put("styleNo", value.styleNo());
        data.put("name", value.name());
        data.put("size", value.size());
        data.put("color", value.color());
        data.put("uomId", value.uomId());
        data.put("referencePrice", value.referencePrice());
        data.put("currencyCode", value.currencyCode());
        return data;
    }
}
