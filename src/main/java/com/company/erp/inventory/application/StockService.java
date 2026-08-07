package com.company.erp.inventory.application;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.inventory.api.StockModels.EligibleReturnSourcePageResponse;
import com.company.erp.inventory.api.StockModels.StockCommandResponse;
import com.company.erp.inventory.api.StockModels.StockDisposalRequest;
import com.company.erp.inventory.api.StockModels.StockMovementPageResponse;
import com.company.erp.inventory.api.StockModels.StockPositionPageResponse;
import com.company.erp.inventory.api.StockModels.StockReturnRequest;
import com.company.erp.inventory.infrastructure.StockJdbcRepository;
import com.company.erp.production.api.ProductionModels.StockPositionResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {

    private final StockJdbcRepository repository;
    private final AuditEventWriter auditWriter;

    public StockService(StockJdbcRepository repository, AuditEventWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    public StockPositionPageResponse list(int page, int size, List<String> sort,
            UUID customerId, UUID productionOrderId, Boolean inStock) {
        AdminQuery.validatePage(page, size);
        return repository.list(page, size, customerId, productionOrderId, inStock,
                repository.positionSort(sort));
    }

    public StockPositionResponse get(UUID id) {
        return repository.find(id)
                .orElseThrow(() -> new ResourceNotFoundException("stock position", id.toString()));
    }

    public StockMovementPageResponse movements(UUID id, int page, int size, List<String> sort,
            String movementType, java.time.LocalDate from, java.time.LocalDate to) {
        get(id);
        AdminQuery.validatePage(page, size);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                    "businessDateFrom must not be after businessDateTo.");
        }
        String type = AdminQuery.enumValue(movementType,
                Set.of("PRODUCTION", "DELIVERY", "DELIVERY_REVERSAL", "RETURN", "DISPOSE"),
                "movementType");
        return repository.movements(id, page, size, type, from, to, repository.movementSort(sort));
    }

    public EligibleReturnSourcePageResponse eligibleReturns(
            UUID id, int page, int size, List<String> sort) {
        get(id);
        AdminQuery.validatePage(page, size);
        return repository.eligibleReturns(id, page, size, repository.returnSort(sort));
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public StockCommandResponse dispose(UUID id, long expectedVersion, StockDisposalRequest request,
            UUID commandId, ErpPrincipal actor, String requestId) {
        repository.coordinate();
        var position = repository.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("stock position", id.toString()));
        verifyVersion(position.version(), expectedVersion);
        BigDecimal quantity = new BigDecimal(request.quantity());
        if (quantity.compareTo(position.currentQty()) > 0) {
            throw new ApiException(ApiErrorCode.DISPOSAL_EXCEEDS_STOCK,
                    "Disposal quantity exceeds current stock.");
        }
        var movement = repository.dispose(position, quantity, request.businessDate(), request.reason(),
                commandId, actor.user().id());
        StockPositionResponse stock = get(id);
        auditWriter.write(actor.user().id(), "DISPOSE", "STOCK_POSITION", id, requestId,
                request.reason(), null, java.util.Map.of("quantity", request.quantity(),
                        "currentQty", stock.currentQty()));
        repository.forceDeferredConstraints();
        return new StockCommandResponse(stock, movement);
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public StockCommandResponse recordReturn(UUID id, long expectedVersion, StockReturnRequest request,
            UUID commandId, ErpPrincipal actor, String requestId) {
        repository.coordinate();
        var position = repository.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("stock position", id.toString()));
        verifyVersion(position.version(), expectedVersion);
        var source = repository.lockDeliverySource(request.deliveryNoteItemId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.RETURN_LIMIT_EXCEEDED,
                        "The Delivery source is not eligible for Return."));
        if (!id.equals(source.positionId()) || !"POSTED".equals(source.status())) {
            throw new ApiException(ApiErrorCode.RETURN_LIMIT_EXCEEDED,
                    "Return requires a POSTED Delivery line from the same Stock Position.");
        }
        BigDecimal quantity = new BigDecimal(request.quantity());
        if (repository.returnedForSource(source.id()).add(quantity).compareTo(source.deliveryQty()) > 0) {
            throw new ApiException(ApiErrorCode.RETURN_LIMIT_EXCEEDED,
                    "Return quantity exceeds the net delivered quantity.");
        }
        var movement = repository.recordReturn(position, source, quantity, request.businessDate(),
                request.reason(), commandId, actor.user().id());
        StockPositionResponse stock = get(id);
        auditWriter.write(actor.user().id(), "RETURN", "STOCK_POSITION", id, requestId,
                request.reason(), null, java.util.Map.of("quantity", request.quantity(),
                        "deliveryNoteItemId", request.deliveryNoteItemId(),
                        "currentQty", stock.currentQty()));
        repository.forceDeferredConstraints();
        return new StockCommandResponse(stock, movement);
    }

    private static void verifyVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT,
                    "The Stock Position changed after it was read.");
        }
    }
}
