package com.company.erp.delivery.application;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.delivery.api.DeliveryModels.DeliveryCommandRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteCreateRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteItemRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteUpdateRequest;
import com.company.erp.delivery.api.DeliveryModels.DeliveryReverseResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliverySourcePageResponse;
import com.company.erp.delivery.infrastructure.DeliveryJdbcRepository;
import com.company.erp.delivery.infrastructure.DeliveryJdbcRepository.ItemRow;
import com.company.erp.delivery.infrastructure.DeliveryJdbcRepository.SourceRow;
import com.company.erp.system.application.DocumentNumberAllocator;
import com.company.erp.system.application.DocumentType;
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
    private final DocumentNumberAllocator numbers;
    private final AuditEventWriter auditWriter;

    public DeliveryService(DeliveryJdbcRepository repository, DocumentNumberAllocator numbers,
            AuditEventWriter auditWriter) {
        this.repository = repository;
        this.numbers = numbers;
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

    /**
     * Post is the only path that consumes finished stock. It re-locks every source position in
     * ascending UUID order, re-checks capacity against the committed balance rather than what the
     * draft saw, snapshots the authoritative monthly rate, and writes the movements, the position
     * updates and the number allocation in one transaction.
     */
    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public DeliveryNoteResponse post(UUID id, long expectedVersion, DeliveryCommandRequest request,
            UUID commandId, ErpPrincipal actor, String requestId) {
        repository.coordinate();
        var header = repository.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("delivery note", id.toString()));
        if (header.version() != expectedVersion) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
                    "The Delivery Note changed after it was read.");
        }
        if ("POSTED".equals(header.status())) {
            throw new ApiException(ApiErrorCode.DELIVERY_ALREADY_POSTED,
                    "The Delivery Note is already posted.");
        }
        if ("REVERSED".equals(header.status())) {
            throw new ApiException(ApiErrorCode.DELIVERY_ALREADY_REVERSED,
                    "The Delivery Note is reversed.");
        }

        DeliveryNoteResponse before = get(id);
        var rate = repository.activeRateForMonth(before.deliveryDate())
                .orElseThrow(() -> new ApiException(ApiErrorCode.EXCHANGE_RATE_MISSING,
                        "No active monthly exchange rate covers the delivery date."));

        List<ItemRow> items = repository.itemsForPost(id);
        List<UUID> positionIds = items.stream().map(ItemRow::stockPositionId).sorted().toList();
        Map<UUID, SourceRow> positions = new LinkedHashMap<>();
        repository.lockSources(positionIds).forEach(source -> positions.put(source.id(), source));
        if (positions.size() != positionIds.size()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                    "Every delivery line must reference an existing Stock Position.");
        }
        for (ItemRow item : items) {
            SourceRow position = positions.get(item.stockPositionId());
            if (item.deliveryQty().compareTo(position.currentQty()) > 0) {
                throw new ApiException(ApiErrorCode.INSUFFICIENT_STOCK,
                        "Delivery quantity exceeds the available stock for this position.");
            }
        }

        String deliveryNo = numbers.next(DocumentType.DELIVERY, before.deliveryDate().getYear());
        for (ItemRow item : items) {
            repository.consumeStock(positions.get(item.stockPositionId()), item, id,
                    before.deliveryDate(), commandId, actor.user().id());
        }
        if (repository.markPosted(id, expectedVersion, deliveryNo, rate, actor.user().id()) != 1) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
                    "The Delivery Note changed after it was read.");
        }
        repository.event(id, "POSTED", actor.user().id(), trim(request.reason()));
        DeliveryNoteResponse response = get(id);
        auditWriter.write(actor.user().id(), "POST", "DELIVERY_NOTE", id, requestId,
                trim(request.reason()), summary(before), summary(response));
        repository.forceDeferredConstraints();
        return response;
    }

    /**
     * Reverse restores the delivered stock and records why, then hands back one editable draft so
     * the shipment can be reissued. Nothing is deleted: the source stays REVERSED with its number,
     * its movements, and its history, and the replacement gets no number until it is posted itself.
     */
    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public DeliveryReverseResponse reverse(UUID id, long expectedVersion, DeliveryCommandRequest request,
            UUID commandId, ErpPrincipal actor, String requestId) {
        repository.coordinate();
        var header = repository.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("delivery note", id.toString()));
        if (header.version() != expectedVersion) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
                    "The Delivery Note changed after it was read.");
        }
        if ("REVERSED".equals(header.status())) {
            throw new ApiException(ApiErrorCode.DELIVERY_ALREADY_REVERSED,
                    "The Delivery Note is already reversed.");
        }
        if (!"POSTED".equals(header.status())) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only a POSTED Delivery Note can be reversed.");
        }
        if (repository.hasReturns(id)) {
            throw new ApiException(ApiErrorCode.DELIVERY_HAS_RETURNS,
                    "A Delivery Note with customer returns cannot be reversed.");
        }

        DeliveryNoteResponse before = get(id);
        List<ItemRow> items = repository.itemsForReversal(id);
        List<UUID> positionIds = items.stream().map(ItemRow::stockPositionId).sorted().toList();
        Map<UUID, SourceRow> positions = new LinkedHashMap<>();
        repository.lockSources(positionIds).forEach(source -> positions.put(source.id(), source));

        String reason = trim(request.reason());
        for (ItemRow item : items) {
            repository.restoreStock(positions.get(item.stockPositionId()), item, id,
                    before.deliveryDate(), commandId, reason, actor.user().id());
        }
        if (repository.markReversed(id, expectedVersion, reason, actor.user().id()) != 1) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
                    "The Delivery Note changed after it was read.");
        }
        repository.event(id, "REVERSED", actor.user().id(), reason);

        UUID replacementId = createReplacementDraft(id, before, actor);
        DeliveryNoteResponse reversed = get(id);
        DeliveryNoteResponse replacement = get(replacementId);
        auditWriter.write(actor.user().id(), "REVERSE", "DELIVERY_NOTE", id, requestId, reason,
                summary(before), summary(reversed));
        repository.forceDeferredConstraints();
        return new DeliveryReverseResponse(reversed, replacement);
    }

    /**
     * The replacement copies business intent only. Totals are recomputed from the copied lines
     * rather than inherited, so a stale posted total can never become a draft's opening state.
     */
    private UUID createReplacementDraft(UUID sourceId, DeliveryNoteResponse source, ErpPrincipal actor) {
        var lines = repository.replacementLines(sourceId);
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<UUID, SourceRow> positions = new LinkedHashMap<>();
        repository.lockSources(lines.stream()
                        .map(line -> line.stockPositionId())
                        .sorted()
                        .toList())
                .forEach(position -> positions.put(position.id(), position));
        for (var line : lines) {
            totalQty = totalQty.add(line.deliveryQty());
            totalAmount = totalAmount.add(
                    line.deliveryQty().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP));
        }

        UUID replacementId = repository.insertDraft(
                source.customerId(), source.customerName(), source.customerAddress(),
                source.deliveryDate(), source.currencyCode(), new BigDecimal(source.vatPercent()),
                source.remark(), totalQty.setScale(4, RoundingMode.UNNECESSARY),
                totalAmount.setScale(2, RoundingMode.UNNECESSARY), sourceId, actor.user().id());
        int lineNo = 1;
        for (var line : lines) {
            repository.insertItem(replacementId, lineNo++, positions.get(line.stockPositionId()),
                    line.deliveryQty(), line.unitPrice(),
                    line.deliveryQty().multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP));
        }
        repository.event(replacementId, "CREATED", actor.user().id(), "replaces reversed delivery");
        repository.event(sourceId, "REPLACED", actor.user().id(), "replacement draft created");
        return replacementId;
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
