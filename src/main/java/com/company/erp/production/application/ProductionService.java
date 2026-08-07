package com.company.erp.production.application;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.inventory.infrastructure.StockPositionCreationRepository;
import com.company.erp.production.api.ProductionModels.PageMetadata;
import com.company.erp.production.api.ProductionModels.ProductionCommandRequest;
import com.company.erp.production.api.ProductionModels.ProductionConfigurationRequest;
import com.company.erp.production.api.ProductionModels.ProductionProcessRequest;
import com.company.erp.production.api.ProductionModels.ProductionEventPageResponse;
import com.company.erp.production.api.ProductionModels.ProductionFinishResponse;
import com.company.erp.production.api.ProductionModels.ProductionGroupCreateRequest;
import com.company.erp.production.api.ProductionModels.ProductionGroupMemberRequest;
import com.company.erp.production.api.ProductionModels.ProductionGroupResponse;
import com.company.erp.production.api.ProductionModels.ProductionOrderPageResponse;
import com.company.erp.production.api.ProductionModels.ProductionOrderResponse;
import com.company.erp.production.infrastructure.ProductionJdbcRepository;
import com.company.erp.production.infrastructure.ProductionJdbcRepository.GroupRow;
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
public class ProductionService {

    private final ProductionJdbcRepository repository;
    private final StockPositionCreationRepository stockRepository;
    private final AuditEventWriter auditWriter;

    public ProductionService(
            ProductionJdbcRepository repository,
            StockPositionCreationRepository stockRepository,
            AuditEventWriter auditWriter) {
        this.repository = repository;
        this.stockRepository = stockRepository;
        this.auditWriter = auditWriter;
    }

    public ProductionOrderPageResponse list(int page, int size, List<String> requestedSort, String status,
            UUID buyerOrderId, String productionNo, String productKind, String groupNo) {
        AdminQuery.validatePage(page, size);
        String normalizedStatus = AdminQuery.enumValue(status, Set.of("OPEN", "FINISHED", "CANCELLED"), "status");
        String normalizedKind = AdminQuery.enumValue(productKind, Set.of("PRINT", "WOVEN"), "productKind");
        List<String> sort = repository.sort(requestedSort);
        long total = repository.count(normalizedStatus, buyerOrderId, trim(productionNo), normalizedKind, trim(groupNo));
        return new ProductionOrderPageResponse(repository.list(page, size, normalizedStatus, buyerOrderId,
                trim(productionNo), normalizedKind, trim(groupNo), sort), new PageMetadata(page, size, total,
                AdminQuery.totalPages(total, size)), AdminQuery.filters("status", normalizedStatus, "buyerOrderId", buyerOrderId,
                "productionNo", trim(productionNo), "productKind", normalizedKind, "groupNo", trim(groupNo)), sort);
    }

    public ProductionOrderResponse get(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResourceNotFoundException("production order", id.toString()));
    }

    public ProductionEventPageResponse events(UUID id, int page, int size, List<String> requestedSort) {
        get(id);
        AdminQuery.validatePage(page, size);
        return repository.events(id, page, size, repository.eventSort(requestedSort));
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public ProductionOrderResponse configure(
            UUID id,
            long expectedVersion,
            ProductionConfigurationRequest request,
            ErpPrincipal actor,
            String requestId) {
        validateConfigurationShape(request);
        repository.coordinate();
        ProductionOrderResponse order = repository.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("production order", id.toString()));
        if (order.version() != expectedVersion) {
            conflict();
        }
        if (!"OPEN".equals(order.status())) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only an OPEN Production Order can be configured.");
        }
        if (!order.productKind().equals(request.productKind())) {
            invalidConfiguration("Configuration product kind does not match the Production Order.");
        }
        validateProcesses(request.processes());
        List<UUID> processIds = request.processes().stream()
                .map(ProductionProcessRequest::processId)
                .sorted()
                .toList();
        if (!processIds.isEmpty() && repository.lockActiveProcesses(processIds).size() != processIds.size()) {
            invalidConfiguration("Every Production process must reference an ACTIVE Process master.");
        }

        Map<String, Object> before = configurationSummary(order);
        repository.replaceConfiguration(id, request, actor.user().id());
        repository.incrementVersion(id, expectedVersion, actor.user().id());
        repository.event(id, "CONFIGURED", actor.user().id(), request.remark());
        ProductionOrderResponse response = get(id);
        auditWriter.write(actor.user().id(), "CONFIGURE", "PRODUCTION_ORDER", id, requestId,
                request.remark(), before, configurationSummary(response));
        repository.forceDeferredConstraints();
        return response;
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public ProductionFinishResponse finish(
            UUID id,
            long expectedVersion,
            ProductionCommandRequest request,
            UUID commandId,
            ErpPrincipal actor,
            String requestId,
            java.time.LocalDate businessDate) {
        repository.coordinate();
        ProductionOrderResponse order = repository.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("production order", id.toString()));
        if (order.version() != expectedVersion) {
            conflict();
        }
        if ("FINISHED".equals(order.status())) {
            throw new ApiException(ApiErrorCode.PRODUCTION_ALREADY_FINISHED,
                    "The Production Order is already finished.");
        }
        if (!"OPEN".equals(order.status())) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "Only an OPEN Production Order can be finished.");
        }
        if (!repository.hasMatchingConfiguration(id, order.productKind())) {
            throw new ApiException(ApiErrorCode.INVALID_STATE_TRANSITION,
                    "A matching Production configuration is required before finish.");
        }
        repository.finish(id, expectedVersion, actor.user().id());
        var stock = stockRepository.createForFinishedProduction(id, commandId, businessDate, actor.user().id());
        repository.event(id, "FINISHED", actor.user().id(), trim(request.reason()));
        ProductionOrderResponse finished = get(id);
        auditWriter.write(actor.user().id(), "FINISH", "PRODUCTION_ORDER", id, requestId,
                trim(request.reason()), configurationSummary(order), configurationSummary(finished));
        repository.forceDeferredConstraints();
        return new ProductionFinishResponse(finished, stock);
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public ProductionGroupResponse group(ProductionGroupCreateRequest request, ErpPrincipal actor,
            String requestId, String commandKey) {
        validateMemberInput(request.members());
        repository.coordinate();
        List<UUID> orderedIds = request.members().stream().map(ProductionGroupMemberRequest::productionOrderId)
                .sorted(Comparator.naturalOrder()).toList();
        List<ProductionOrderResponse> orders = repository.lockAll(orderedIds);
        if (orders.size() != orderedIds.size()) {
            invalidMembership();
        }
        Map<UUID, Long> versions = new LinkedHashMap<>();
        request.members().forEach(member -> versions.put(member.productionOrderId(), member.expectedVersion()));
        for (ProductionOrderResponse order : orders) {
            if (order.version() != versions.get(order.id())) conflict();
        }
        UUID buyerOrderId = orders.getFirst().buyerOrderId();
        if (orders.stream().anyMatch(order -> !buyerOrderId.equals(order.buyerOrderId())
                || !"OPEN".equals(order.status()) || order.groupId() != null)) {
            invalidMembership();
        }
        String groupNo = repository.nextGroupNo(buyerOrderId);
        UUID groupId = repository.createGroup(buyerOrderId, groupNo, actor.user().id());
        repository.assignGroup(orderedIds, groupId, actor.user().id(), trim(request.reason()));
        ProductionGroupResponse response = repository.group(groupId);
        auditWriter.write(actor.user().id(), "GROUP", "PRODUCTION_GROUP", groupId, requestId, trim(request.reason()),
                null, groupSummary(response));
        repository.forceDeferredConstraints();
        return response;
    }

    @Transactional(noRollbackFor = {ApiException.class, ResourceNotFoundException.class})
    public ProductionGroupResponse ungroup(UUID groupId, long version, ProductionCommandRequest request,
            ErpPrincipal actor, String requestId, String commandKey) {
        repository.coordinate();
        GroupRow group = repository.lockGroup(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("production group", groupId.toString()));
        if (group.version() != version) conflict();
        List<ProductionOrderResponse> members = repository.lockGroupMembers(groupId);
        if (members.size() < 2 || members.stream().anyMatch(member -> !"OPEN".equals(member.status()))) invalidMembership();
        List<UUID> ids = members.stream().map(ProductionOrderResponse::id).sorted().toList();
        repository.clearGroup(ids, groupId, actor.user().id(), trim(request.reason()));
        List<ProductionOrderResponse> ungrouped = ids.stream().map(this::get).toList();
        repository.deleteEmptyGroup(groupId, version);
        ProductionGroupResponse response = repository.removedGroup(group, ungrouped);
        auditWriter.write(actor.user().id(), "UNGROUP", "PRODUCTION_GROUP", groupId, requestId, trim(request.reason()),
                groupSummary(repository.removedGroup(group, members)), groupSummary(response));
        repository.forceDeferredConstraints();
        return response;
    }

    private static void validateMemberInput(List<ProductionGroupMemberRequest> members) {
        if (members == null || members.size() < 2 || members.size() > 50) invalidMembership();
        Set<UUID> values = new HashSet<>();
        for (ProductionGroupMemberRequest member : members) {
            if (member == null || member.productionOrderId() == null
                    || member.expectedVersion() < 0 || !values.add(member.productionOrderId())) invalidMembership();
        }
    }

    private static void validateConfigurationShape(ProductionConfigurationRequest request) {
        if (request == null || request.productKind() == null
                || request.processes() == null || request.yarnLines() == null) {
            invalidConfiguration("Production configuration is incomplete.");
        }
        if ("PRINT".equals(request.productKind())) {
            if (request.bim() != null || request.tenDia() != null || request.matDo() != null
                    || request.pick() != null || request.xNgangMm() != null || request.yDocMm() != null
                    || request.haiDaMm() != null || request.logoXMm() != null || request.logoYMm() != null
                    || request.hoPercent() != null || !empty(request.weaveTypes()) || !request.yarnLines().isEmpty()) {
                invalidConfiguration("PRINT configuration cannot contain WOVEN fields.");
            }
        } else if ("WOVEN".equals(request.productKind())) {
            if (request.materialSource() != null || request.orderKind() != null) {
                invalidConfiguration("WOVEN configuration cannot contain PRINT fields.");
            }
        } else {
            invalidConfiguration("Unsupported Production product kind.");
        }
    }

    private static void validateProcesses(List<ProductionProcessRequest> processes) {
        Set<UUID> processIds = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        for (ProductionProcessRequest process : processes) {
            if (process == null || process.processId() == null || process.sequenceNo() == null
                    || !processIds.add(process.processId()) || !sequences.add(process.sequenceNo())) {
                invalidConfiguration("Production process IDs and sequence numbers must be unique.");
            }
        }
    }

    private static boolean empty(List<?> values) {
        return values == null || values.isEmpty();
    }

    private static Map<String, Object> configurationSummary(ProductionOrderResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", response.id());
        summary.put("version", response.version());
        summary.put("status", response.status());
        summary.put("productKind", response.productKind());
        summary.put("processCount", response.configuration().processes().size());
        summary.put("yarnLineCount", response.configuration().yarnLines().size());
        return summary;
    }

    private static Map<String, Object> groupSummary(ProductionGroupResponse response) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", response.id());
        summary.put("version", response.version());
        summary.put("groupNo", response.groupNo());
        summary.put("buyerOrderId", response.buyerOrderId());
        summary.put("memberIds", response.members().stream().map(
                com.company.erp.production.api.ProductionModels.ProductionGroupMemberResponse::id).toList());
        return summary;
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static void conflict() { throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "The Production Order changed after it was read."); }
    private static void invalidMembership() { throw new ApiException(ApiErrorCode.GROUP_MEMBERSHIP_INVALID, "Production Group members must be unique, OPEN and ungrouped Production Orders from one Buyer Order."); }
    private static void invalidConfiguration(String detail) { throw new ApiException(ApiErrorCode.VALIDATION_FAILED, detail); }
}
