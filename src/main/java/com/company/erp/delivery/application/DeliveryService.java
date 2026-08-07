package com.company.erp.delivery.application;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteCreateRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteItemRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteUpdateRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliverySourcePageResponse;
import com.company.erp.delivery.infrastructure.DeliveryJdbcRepository;
import com.company.erp.delivery.infrastructure.DeliveryJdbcRepository.SourceRow;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryService {

    private final DeliveryJdbcRepository repository;
    private final AuditEventWriter auditWriter;

    public DeliveryService(DeliveryJdbcRepository repository, AuditEventWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    public DeliverySourcePageResponse sources(int page, int size, List<String> sort,
            UUID customerId, String sysPoNo, String buyerPo, Boolean inStock) {
        AdminQuery.validatePage(page, size);
        return repository.sources(page, size, customerId, trim(sysPoNo), trim(buyerPo), inStock,
                repository.sourceSort(sort));
    }

    public DeliveryNoteResponse get(UUID id) {
        return repository.find(id)
                .orElseThrow(() -> new ResourceNotFoundException("delivery note", id.toString()));
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public DeliveryNoteResponse create(DeliveryNoteCreateRequest request, ErpPrincipal actor,
            String requestId) {
        repository.coordinate();
        Resolved resolved = resolve(request.items(), request.vatPercent());
        UUID id = repository.insertDraft(
                resolved.customerId(), resolved.customerName(), resolved.customerAddress(),
                request.deliveryDate(), resolved.currencyCode(), new BigDecimal(request.vatPercent()),
                trim(request.remark()), resolved.totalQty(), resolved.totalAmount(), null,
                actor.user().id());
        persistItems(id, resolved);
        repository.event(id, "CREATED", actor.user().id(), trim(request.remark()));
        DeliveryNoteResponse response = get(id);
        auditWriter.write(actor.user().id(), "CREATE", "DELIVERY_NOTE", id, requestId,
                trim(request.remark()), null, summary(response));
        repository.forceDeferredConstraints();
        return response;
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public DeliveryNoteResponse update(UUID id, long expectedVersion, DeliveryNoteUpdateRequest request,
            ErpPrincipal actor, String requestId) {
        repository.coordinate();
        var header = repository.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("delivery note", id.toString()));
        if (header.version() != expectedVersion) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
                    "The Delivery Note changed after it was read.");
        }
        if (!"DRAFT".equals(header.status())) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only a DRAFT Delivery Note can be updated.");
        }
        DeliveryNoteResponse before = get(id);
        Resolved resolved = resolve(request.items(), request.vatPercent());
        repository.deleteItems(id);
        if (repository.updateDraft(id, expectedVersion, request.deliveryDate(),
                new BigDecimal(request.vatPercent()), trim(request.remark()), resolved.totalQty(),
                resolved.totalAmount(), actor.user().id()) != 1) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
                    "The Delivery Note changed after it was read.");
        }
        persistItems(id, resolved);
        repository.event(id, "UPDATED_DRAFT", actor.user().id(), trim(request.remark()));
        DeliveryNoteResponse response = get(id);
        auditWriter.write(actor.user().id(), "UPDATE", "DELIVERY_NOTE", id, requestId,
                trim(request.remark()), summary(before), summary(response));
        repository.forceDeferredConstraints();
        return response;
    }

    private void persistItems(UUID deliveryId, Resolved resolved) {
        int lineNo = 1;
        for (ResolvedLine line : resolved.lines()) {
            repository.insertItem(deliveryId, lineNo++, line.source(), line.quantity(),
                    line.unitPrice(), line.amount());
        }
    }

    /**
     * Draft writes trust only the source IDs. Every snapshot, amount and total is re-derived from
     * the locked Stock Positions, so a client cannot post its own prices or totals.
     */
    private Resolved resolve(List<DeliveryNoteItemRequest> items, String vatPercent) {
        Set<UUID> unique = new HashSet<>();
        for (DeliveryNoteItemRequest item : items) {
            if (!unique.add(item.stockPositionId())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                        "Each Stock Position may appear at most once on a Delivery Note.");
            }
        }
        List<UUID> ordered = items.stream()
                .map(DeliveryNoteItemRequest::stockPositionId)
                .sorted(Comparator.naturalOrder())
                .toList();
        Map<UUID, SourceRow> sources = new LinkedHashMap<>();
        repository.lockSources(ordered).forEach(source -> sources.put(source.id(), source));
        if (sources.size() != ordered.size()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                    "Every delivery line must reference an existing Stock Position.");
        }

        UUID customerId = null;
        String currencyCode = null;
        String customerName = null;
        String customerAddress = null;
        List<ResolvedLine> lines = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (DeliveryNoteItemRequest item : items) {
            SourceRow source = sources.get(item.stockPositionId());
            if (customerId == null) {
                customerId = source.customerId();
                currencyCode = source.currencyCode();
                customerName = source.customerName();
                customerAddress = source.customerAddress();
            } else if (!customerId.equals(source.customerId())
                    || !currencyCode.equals(source.currencyCode())) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                        "One Delivery Note carries a single Customer and currency.");
            }
            BigDecimal quantity = new BigDecimal(item.deliveryQty());
            if (quantity.compareTo(source.currentQty()) > 0) {
                throw new ApiException(ApiErrorCode.INSUFFICIENT_STOCK,
                        "Delivery quantity exceeds the available stock for this position.");
            }
            BigDecimal unitPrice = new BigDecimal(item.unitPrice());
            BigDecimal amount = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
            if (amount.precision() - amount.scale() > 16) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                        "Line amount exceeds the supported monetary range.");
            }
            lines.add(new ResolvedLine(source, quantity, unitPrice, amount));
            totalQty = totalQty.add(quantity);
            totalAmount = totalAmount.add(amount);
        }
        return new Resolved(customerId, customerName, customerAddress, currencyCode, lines,
                totalQty.setScale(4, RoundingMode.UNNECESSARY),
                totalAmount.setScale(2, RoundingMode.UNNECESSARY));
    }

    private static Map<String, Object> summary(DeliveryNoteResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", response.id());
        summary.put("version", response.version());
        summary.put("status", response.status());
        summary.put("deliveryNo", response.deliveryNo());
        summary.put("totalQty", response.totalQty());
        summary.put("totalAmount", response.totalAmount());
        summary.put("lineCount", response.items().size());
        return summary;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record ResolvedLine(SourceRow source, BigDecimal quantity, BigDecimal unitPrice,
            BigDecimal amount) { }

    private record Resolved(UUID customerId, String customerName, String customerAddress,
            String currencyCode, List<ResolvedLine> lines, BigDecimal totalQty,
            BigDecimal totalAmount) { }
}
