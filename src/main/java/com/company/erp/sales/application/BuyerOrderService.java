package com.company.erp.sales.application;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.production.infrastructure.ProductionOrderCreationRepository;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCommandRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderConfirmResponse;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCopyRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderCreateRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderItemRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderItemResponse;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderPageResponse;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderResponse;
import com.company.erp.sales.api.BuyerOrderModels.CustomBuyerOrderItemRequest;
import com.company.erp.sales.api.BuyerOrderModels.PageMetadata;
import com.company.erp.sales.api.BuyerOrderModels.ProductionOrderResponse;
import com.company.erp.sales.api.BuyerOrderModels.StandardBuyerOrderItemRequest;
import com.company.erp.sales.api.BuyerOrderModels.BuyerOrderUpdateRequest;
import com.company.erp.sales.infrastructure.BuyerOrderJdbcRepository;
import com.company.erp.sales.infrastructure.BuyerOrderJdbcRepository.CustomerSnapshot;
import com.company.erp.sales.infrastructure.BuyerOrderJdbcRepository.FinishedGoodSnapshot;
import com.company.erp.sales.infrastructure.BuyerOrderJdbcRepository.ResolvedItem;
import com.company.erp.system.application.DocumentNumberAllocator;
import com.company.erp.system.application.DocumentType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuyerOrderService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");

    private final BuyerOrderJdbcRepository repository;
    private final ProductionOrderCreationRepository productionRepository;
    private final DocumentNumberAllocator numbers;
    private final AuditEventWriter auditWriter;
    private final Clock clock;

    @Autowired
    public BuyerOrderService(
            BuyerOrderJdbcRepository repository,
            ProductionOrderCreationRepository productionRepository,
            DocumentNumberAllocator numbers,
            AuditEventWriter auditWriter) {
        this(repository, productionRepository, numbers, auditWriter, Clock.systemUTC());
    }

    BuyerOrderService(
            BuyerOrderJdbcRepository repository,
            ProductionOrderCreationRepository productionRepository,
            DocumentNumberAllocator numbers,
            AuditEventWriter auditWriter,
            Clock clock) {
        this.repository = repository;
        this.productionRepository = productionRepository;
        this.numbers = numbers;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    public BuyerOrderPageResponse list(
            int page, int size, List<String> requestedSort, String status, UUID customerId,
            String sysPoNo, String buyerPo, LocalDate poDateFrom, LocalDate poDateTo) {
        AdminQuery.validatePage(page, size);
        String normalizedStatus = AdminQuery.enumValue(status, java.util.Set.of("STANDBY", "CONFIRMED"), "status");
        if (poDateFrom != null && poDateTo != null && poDateFrom.isAfter(poDateTo)) {
            validation("poDateFrom must not be after poDateTo.");
        }
        List<String> sort = repository.sort(requestedSort);
        long total = repository.count(normalizedStatus, customerId, trim(sysPoNo), trim(buyerPo), poDateFrom, poDateTo);
        return new BuyerOrderPageResponse(
                repository.list(page, size, normalizedStatus, customerId, trim(sysPoNo), trim(buyerPo),
                        poDateFrom, poDateTo, sort),
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters("status", normalizedStatus, "customerId", customerId,
                        "sysPoNo", trim(sysPoNo), "buyerPo", trim(buyerPo),
                        "poDateFrom", poDateFrom, "poDateTo", poDateTo), sort);
    }

    public BuyerOrderResponse get(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResourceNotFoundException("buyer order", id.toString()));
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public BuyerOrderResponse create(
            BuyerOrderCreateRequest request, ErpPrincipal actor, String requestId, String commandKey) {
        Prepared prepared = prepare(request);
        String number = numbers.next(DocumentType.SALES_ORDER, LocalDate.now(clock.withZone(BUSINESS_ZONE)).getYear());
        UUID id = repository.insertOrder(number, prepared.request(), prepared.customer(), prepared.picName(), actor.user().id());
        repository.insertItems(id, 1, prepared.items(), actor.user().id());
        BuyerOrderResponse created = get(id);
        auditWriter.write(actor.user().id(), "CREATE", "BUYER_ORDER", id, requestId, null, null, summary(created));
        repository.forceDeferredConstraints();
        return created;
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public BuyerOrderResponse update(
            UUID id, long version, BuyerOrderUpdateRequest request, ErpPrincipal actor,
            String requestId, String commandKey) {
        repository.coordinateGuardedReferences();
        BuyerOrderResponse before = lock(id);
        requireVersionAndState(before, version, "STANDBY");
        Prepared prepared = prepare(new BuyerOrderCreateRequest(
                request.orderType(), request.customerId(), request.customerContactId(), request.picSource(),
                request.picName(), request.buyerPo(), request.poDate(), request.deliveryDate(), request.items()));
        if (repository.updateHeader(id, version, prepared.request(), prepared.customer(), prepared.picName(), actor.user().id()) != 1) {
            conflict();
        }
        repository.deleteActiveItems(id);
        repository.insertItems(id, before.revision(), prepared.items(), actor.user().id());
        BuyerOrderResponse after = get(id);
        auditWriter.write(actor.user().id(), "UPDATE", "BUYER_ORDER", id, requestId, null,
                summary(before), summary(after));
        repository.forceDeferredConstraints();
        return after;
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public BuyerOrderResponse copy(
            UUID sourceId, long version, BuyerOrderCopyRequest request, ErpPrincipal actor,
            String requestId, String commandKey) {
        repository.coordinateGuardedReferences();
        BuyerOrderResponse source = lock(sourceId);
        if (source.version() != version) {
            conflict();
        }
        List<BuyerOrderItemRequest> inputs = source.items().stream()
                .filter(BuyerOrderItemResponse::activeRevision)
                .map(BuyerOrderService::toRequest)
                .toList();
        LocalDate poDate = request.poDate() == null ? source.poDate() : request.poDate();
        LocalDate deliveryDate = request.deliveryDate() == null ? source.deliveryDate() : request.deliveryDate();
        String buyerPo = trim(request.buyerPo()) == null ? source.buyerPo() : trim(request.buyerPo());
        BuyerOrderCreateRequest create = new BuyerOrderCreateRequest(
                source.orderType(), source.customerId(), source.customerContactId(), source.picSource(),
                source.picName(), buyerPo, poDate, deliveryDate, inputs);
        Prepared prepared = prepare(create);
        String number = numbers.next(
                DocumentType.SALES_ORDER, LocalDate.now(clock.withZone(BUSINESS_ZONE)).getYear());
        UUID copiedId = repository.insertOrder(
                number, prepared.request(), prepared.customer(), prepared.picName(), actor.user().id());
        repository.insertItems(copiedId, 1, prepared.items(), actor.user().id());
        BuyerOrderResponse copied = get(copiedId);
        auditWriter.write(actor.user().id(), "COPY", "BUYER_ORDER", copied.id(), requestId,
                trim(request.reason()), Map.of("sourceId", sourceId), summary(copied));
        repository.forceDeferredConstraints();
        return copied;
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public BuyerOrderConfirmResponse confirm(
            UUID id, long version, BuyerOrderCommandRequest request, ErpPrincipal actor,
            String requestId, String commandKey) {
        repository.coordinateGuardedReferences();
        BuyerOrderResponse before = lock(id);
        requireVersionAndState(before, version, "STANDBY");
        if (!repository.activeReferences(id)) {
            validation("Buyer Order references must remain active when it is confirmed.");
        }
        List<BuyerOrderItemResponse> activeItems = before.items().stream()
                .filter(BuyerOrderItemResponse::activeRevision).toList();
        if (activeItems.isEmpty()) {
            validation("Buyer Order must contain at least one active item.");
        }
        if (repository.confirm(id, version, actor.user().id()) != 1) {
            conflict();
        }
        int year = LocalDate.now(clock.withZone(BUSINESS_ZONE)).getYear();
        List<ProductionOrderResponse> production = new ArrayList<>();
        for (BuyerOrderItemResponse item : activeItems) {
            String productionNo = numbers.next(DocumentType.PRODUCTION, year);
            production.add(productionRepository.create(
                    productionNo, id, item.id(), item.productKind(), item.styleNo(),
                    new BigDecimal(item.productionQty()), actor.user().id(), trim(request.reason())));
        }
        BuyerOrderResponse after = get(id);
        auditWriter.write(actor.user().id(), "CONFIRM", "BUYER_ORDER", id, requestId,
                trim(request.reason()), summary(before), summary(after));
        repository.forceDeferredConstraints();
        return new BuyerOrderConfirmResponse(after, production);
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public BuyerOrderResponse reopen(
            UUID id, long version, BuyerOrderCommandRequest request, ErpPrincipal actor,
            String requestId, String commandKey) {
        repository.coordinateGuardedReferences();
        BuyerOrderResponse before = lock(id);
        requireVersionAndState(before, version, "CONFIRMED");
        repository.lockProductionOrders(id);
        if (repository.downstreamActivity(id)) {
            throw new ApiException(ApiErrorCode.DOWNSTREAM_ACTIVITY_EXISTS,
                    "Buyer Order cannot be reopened after downstream activity exists.");
        }
        List<ResolvedItem> cloned = before.items().stream()
                .filter(BuyerOrderItemResponse::activeRevision)
                .map(BuyerOrderService::resolved)
                .toList();
        repository.cancelOpenProduction(id, actor.user().id(), trim(request.reason()));
        repository.retireItems(id, before.revision(), actor.user().id());
        int revision = before.revision() + 1;
        if (repository.reopen(id, version, revision, actor.user().id()) != 1) {
            conflict();
        }
        repository.insertItems(id, revision, cloned, actor.user().id());
        BuyerOrderResponse after = get(id);
        auditWriter.write(actor.user().id(), "REOPEN", "BUYER_ORDER", id, requestId,
                trim(request.reason()), summary(before), summary(after));
        repository.forceDeferredConstraints();
        return after;
    }

    private Prepared prepare(BuyerOrderCreateRequest request) {
        repository.coordinateGuardedReferences();
        if (request.deliveryDate().isBefore(request.poDate())) {
            validation("deliveryDate must not be before poDate.");
        }
        CustomerSnapshot customer = repository.customer(request.customerId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED,
                        "customerId must reference an ACTIVE Customer."));
        String picName;
        if ("MASTER".equals(request.picSource())) {
            if (request.customerContactId() == null) {
                validation("customerContactId is required when picSource is MASTER.");
            }
            picName = repository.activeContactName(request.customerContactId(), request.customerId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED,
                            "customerContactId must reference an ACTIVE contact of the Customer."));
        } else {
            if (request.customerContactId() != null) {
                validation("customerContactId must be omitted when picSource is CUSTOM.");
            }
            picName = required(request.picName(), "picName");
        }
        BuyerOrderCreateRequest normalized = new BuyerOrderCreateRequest(
                required(request.orderType(), "orderType"), request.customerId(), request.customerContactId(),
                request.picSource(), picName, required(request.buyerPo(), "buyerPo"),
                request.poDate(), request.deliveryDate(), request.items());
        List<ResolvedItem> items = request.items().stream().map(item -> resolve(item, customer)).toList();
        return new Prepared(normalized, customer, picName, items);
    }

    private ResolvedItem resolve(BuyerOrderItemRequest item, CustomerSnapshot customer) {
        BigDecimal quantity = new BigDecimal(item.orderQty());
        BigDecimal price = new BigDecimal(item.unitPrice());
        if (item instanceof StandardBuyerOrderItemRequest standard) {
            FinishedGoodSnapshot good = repository.finishedGood(standard.finishedGoodId())
                    .orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED,
                            "finishedGoodId must reference an ACTIVE Finished Good with an ACTIVE UOM."));
            return ResolvedItem.of(false, good.id(), good.productKind(), good.styleNo(), good.name(),
                    good.size(), good.color(), good.uomId(), good.uomCode(), quantity, price,
                    customer.currencyCode(), trim(item.remark()));
        }
        CustomBuyerOrderItemRequest custom = (CustomBuyerOrderItemRequest) item;
        String uomCode = repository.activeUomCode(custom.uomId())
                .orElseThrow(() -> new ApiException(ApiErrorCode.VALIDATION_FAILED,
                        "uomId must reference an ACTIVE UOM."));
        if (!repository.activeCurrency(custom.currencyCode())) {
            validation("currencyCode must reference an active currency.");
        }
        if (!customer.currencyCode().equals(custom.currencyCode())) {
            validation("Every Buyer Order item must use the Customer currency.");
        }
        return ResolvedItem.of(true, null, custom.productKind(), required(custom.styleNo(), "styleNo"),
                required(custom.name(), "name"), trim(custom.size()), trim(custom.color()), custom.uomId(),
                uomCode, quantity, price, custom.currencyCode(), trim(custom.remark()));
    }

    private BuyerOrderResponse lock(UUID id) {
        return repository.findForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("buyer order", id.toString()));
    }

    private static void requireVersionAndState(BuyerOrderResponse order, long version, String state) {
        if (order.version() != version) {
            conflict();
        }
        if (!state.equals(order.status())) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Buyer Order is not in the required " + state + " state.");
        }
    }

    private static BuyerOrderItemRequest toRequest(BuyerOrderItemResponse item) {
        if (!item.isCustom()) {
            return new StandardBuyerOrderItemRequest(false, item.finishedGoodId(), item.orderQty(),
                    item.unitPrice(), item.remark());
        }
        return new CustomBuyerOrderItemRequest(true, item.productKind(), item.styleNo(), item.name(),
                item.size(), item.color(), item.uomId(), item.orderQty(), item.unitPrice(),
                item.currencyCode(), item.remark());
    }

    private static ResolvedItem resolved(BuyerOrderItemResponse item) {
        return ResolvedItem.of(item.isCustom(), item.finishedGoodId(), item.productKind(), item.styleNo(),
                item.name(), item.size(), item.color(), item.uomId(), item.uomCode(),
                new BigDecimal(item.orderQty()), new BigDecimal(item.unitPrice()),
                item.currencyCode(), item.remark());
    }

    private static Map<String, Object> summary(BuyerOrderResponse value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", value.id());
        data.put("version", value.version());
        data.put("sysPoNo", value.sysPoNo());
        data.put("revision", value.revision());
        data.put("status", value.status());
        data.put("customerId", value.customerId());
        data.put("buyerPo", value.buyerPo());
        data.put("itemCount", value.items().size());
        return data;
    }

    private static String required(String value, String field) {
        String normalized = trim(value);
        if (normalized == null) {
            validation(field + " is required.");
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validation(String detail) {
        throw new ApiException(ApiErrorCode.VALIDATION_FAILED, detail);
    }

    private static void conflict() {
        throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "The Buyer Order changed after it was read.");
    }

    private record Prepared(
            BuyerOrderCreateRequest request, CustomerSnapshot customer,
            String picName, List<ResolvedItem> items) {
    }
}
