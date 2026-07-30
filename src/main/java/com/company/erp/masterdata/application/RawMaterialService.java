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
import com.company.erp.masterdata.api.MasterDataModels.PageResponse;
import com.company.erp.masterdata.api.MasterDataModels.RawMaterialRequest;
import com.company.erp.masterdata.api.MasterDataModels.RawMaterialResponse;
import com.company.erp.masterdata.infrastructure.RawMaterialJdbcRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raw Material is master data only in Phase 1. No inventory, purchase or usage relationship exists
 * yet, so no lifecycle guard is derived; a guard must not be invented from a client-supplied flag.
 */
@Service
public class RawMaterialService {

    private final RawMaterialJdbcRepository repository;
    private final AuditEventWriter auditWriter;

    public RawMaterialService(RawMaterialJdbcRepository repository, AuditEventWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    public PageResponse<RawMaterialResponse> list(
            int page, int size, List<String> requestedSort, String status, String code, String name, UUID supplierId) {
        AdminQuery.validatePage(page, size);
        String normalizedStatus = MasterDataSupport.status(status);
        String normalizedCode = code == null || code.isBlank() ? null : MasterDataSupport.canonicalCode(code);
        String normalizedName = MasterDataSupport.optionalText(name);
        List<String> sort = repository.sort(requestedSort);
        return MasterDataSupport.page(
                repository.list(page, size, normalizedStatus, normalizedCode, normalizedName, supplierId, sort),
                page, size,
                repository.count(normalizedStatus, normalizedCode, normalizedName, supplierId),
                AdminQuery.filters("status", normalizedStatus, "code", normalizedCode,
                        "name", normalizedName, "supplierId", supplierId),
                sort);
    }

    public RawMaterialResponse get(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResourceNotFoundException("raw material", id.toString()));
    }

    @Transactional
    public RawMaterialResponse create(RawMaterialRequest request, ErpPrincipal actor, String requestId) {
        RawMaterialRequest normalized = normalized(request);
        validateReferences(normalized, null);
        if (repository.codeExists(normalized.code(), null)) {
            duplicate();
        }
        UUID id = repository.create(normalized, actor.user().id());
        RawMaterialResponse created = get(id);
        auditWriter.write(actor.user().id(), "CREATE", "RAW_MATERIAL", id, requestId, null, null, summary(created));
        return created;
    }

    @Transactional
    public RawMaterialResponse update(
            UUID id, long version, RawMaterialRequest request, ErpPrincipal actor, String requestId) {
        RawMaterialResponse before = get(id);
        RawMaterialRequest normalized = normalized(request);
        validateReferences(normalized, before);
        if (repository.codeExists(normalized.code(), id)) {
            duplicate();
        }
        if (repository.update(id, version, normalized, actor.user().id()) != 1) {
            MasterDataSupport.staleOrMissing(repository.find(id).isPresent());
        }
        RawMaterialResponse after = get(id);
        auditWriter.write(actor.user().id(), "UPDATE", "RAW_MATERIAL", id, requestId, null,
                summary(before), summary(after));
        return after;
    }

    @Transactional
    public RawMaterialResponse archive(UUID id, long version, ErpPrincipal actor, String requestId) {
        RawMaterialResponse before = get(id);
        if (repository.archive(id, version, actor.user().id()) != 1) {
            MasterDataSupport.staleOrMissing(repository.find(id).isPresent());
        }
        RawMaterialResponse after = get(id);
        auditWriter.write(actor.user().id(), "ARCHIVE", "RAW_MATERIAL", id, requestId, null,
                summary(before), summary(after));
        return after;
    }

    private static RawMaterialRequest normalized(RawMaterialRequest request) {
        return new RawMaterialRequest(
                MasterDataSupport.optionalText(request.category()),
                MasterDataSupport.canonicalCode(request.code()),
                MasterDataSupport.requiredText(request.name()),
                MasterDataSupport.optionalText(request.specification()),
                MasterDataSupport.optionalText(request.size()),
                MasterDataSupport.optionalText(request.color()),
                request.uomId(),
                request.referencePrice(),
                MasterDataSupport.canonicalCode(request.currencyCode()),
                request.supplierId(),
                request.safetyStockQty(),
                MasterDataSupport.optionalText(request.remark()));
    }

    /**
     * Archived references cannot be newly selected. References that a record already carries stay
     * usable so historical rows remain editable after a master is archived.
     */
    private void validateReferences(RawMaterialRequest request, RawMaterialResponse before) {
        if (before == null || !request.uomId().equals(before.uomId())) {
            MasterDataSupport.requireActive(repository.uomStatus(request.uomId()), "uomId");
        }
        if (request.supplierId() != null
                && (before == null || !request.supplierId().equals(before.supplierId()))) {
            MasterDataSupport.requireActive(repository.supplierStatus(request.supplierId()), "supplierId");
        }
        if (before == null || !request.currencyCode().equals(before.currencyCode())) {
            MasterDataSupport.requireActiveCurrency(repository.currencyActive(request.currencyCode()));
        }
    }

    private static void duplicate() {
        throw new ApiException(ApiErrorCode.DUPLICATE_BUSINESS_KEY,
                "A resource with the same business key already exists.");
    }

    private static Map<String, Object> summary(RawMaterialResponse value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", value.id());
        data.put("version", value.version());
        data.put("status", value.status());
        data.put("category", value.category());
        data.put("code", value.code());
        data.put("name", value.name());
        data.put("specification", value.specification());
        data.put("size", value.size());
        data.put("color", value.color());
        data.put("uomId", value.uomId());
        data.put("referencePrice", value.referencePrice());
        data.put("currencyCode", value.currencyCode());
        data.put("supplierId", value.supplierId());
        data.put("safetyStockQty", value.safetyStockQty());
        data.put("remark", value.remark());
        return data;
    }
}
