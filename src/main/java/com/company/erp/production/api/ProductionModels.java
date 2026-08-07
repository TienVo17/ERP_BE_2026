package com.company.erp.production.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProductionModels {

    private ProductionModels() {
    }

    public record ProductionGroupMemberRequest(
            @NotNull UUID productionOrderId,
            @NotNull @Min(0) long expectedVersion) {
    }

    public record ProductionGroupCreateRequest(
            @NotEmpty @Size(min = 2, max = 50) List<@Valid ProductionGroupMemberRequest> members,
            @Size(max = 200) String reason) {
    }

    public record ProductionCommandRequest(@Size(max = 200) String reason) {
    }

    public record ProductionProcessRequest(
            @NotNull UUID processId,
            @NotNull @Min(1) Integer sequenceNo,
            @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String speed) {
    }

    public record ProductionYarnLineRequest(
            @Size(max = 120) String yarn,
            @Size(max = 120) String yarnCode,
            @Size(max = 120) String denier) {
    }

    public record ProductionConfigurationRequest(
            @NotNull @Pattern(regexp = "^(PRINT|WOVEN)$") String productKind,
            @Pattern(regexp = "^(STOCK|PURCHASE)$") String materialSource,
            @Pattern(regexp = "^(LAYOUT|SAMPLE|NEW_ORDER|REPEAT_ORDER|SECOND_PRODUCTION)$") String orderKind,
            @Pattern(regexp = "^(WHITE|BLACK|100D|75D|50D)$") String bim,
            @Size(max = 120) String tenDia,
            @Size(max = 120) String matDo,
            @Size(max = 120) String pick,
            @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String xNgangMm,
            @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String yDocMm,
            @Pattern(regexp = "^\\d{1,14}(\\.\\d{1,4})?$") String haiDaMm,
            @Pattern(regexp = "^\\d{1,14}(\\.\\d{1,4})?$") String logoXMm,
            @Pattern(regexp = "^\\d{1,14}(\\.\\d{1,4})?$") String logoYMm,
            @Pattern(regexp = "^(100(\\.0{1,4})?|([0-9]|[1-9][0-9])(\\.\\d{1,4})?)$") String hoPercent,
            @Size(max = 4) List<@Pattern(regexp = "^(THUONG|HAI_DA|SATIN|SATIN_HAI_DA)$") String> weaveTypes,
            @NotNull @Size(max = 100) List<@Valid ProductionProcessRequest> processes,
            @NotNull @Size(max = 100) List<@Valid ProductionYarnLineRequest> yarnLines,
            @Size(max = 200) String remark) {
    }

    public record ProductionProcessResponse(UUID processId, int sequenceNo, String speed) {
    }

    public record ProductionYarnLineResponse(String yarn, String yarnCode, String denier) {
    }

    public record ProductionConfigurationResponse(
            String productKind, String materialSource, String orderKind,
            String bim, String tenDia, String matDo, String pick,
            String xNgangMm, String yDocMm, String haiDaMm,
            String logoXMm, String logoYMm, String hoPercent,
            List<String> weaveTypes, List<ProductionProcessResponse> processes,
            List<ProductionYarnLineResponse> yarnLines, String remark) {
        public static ProductionConfigurationResponse empty(String productKind) {
            return new ProductionConfigurationResponse(productKind, null, null, null, null, null, null,
                    null, null, null, null, null, null, List.of(), List.of(), List.of(), null);
        }
    }

    public record ProductionEventResponse(
            UUID id, String eventType, UUID actorUserId, String reason, Instant occurredAt) {
    }

    public record ProductionOrderResponse(
            UUID id, long version, String productionNo, String productNo, String qrValue,
            UUID groupId, String groupNo, UUID buyerOrderId, UUID buyerOrderItemId,
            String productKind, String plannedQty, String producedQty, String status,
            ProductionConfigurationResponse configuration, List<ProductionEventResponse> events,
            Instant finishedAt, Instant createdAt, Instant updatedAt) {
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record ProductionOrderPageResponse(
            List<ProductionOrderResponse> items, PageMetadata page,
            Map<String, Object> filters, List<String> sort) {
    }

    public record ProductionEventPageResponse(
            List<ProductionEventResponse> items, PageMetadata page,
            Map<String, Object> filters, List<String> sort) {
    }

    public record ProductionGroupMemberResponse(
            UUID id,
            long version,
            String productionNo,
            String productNo,
            UUID groupId,
            String groupNo,
            UUID buyerOrderId,
            UUID buyerOrderItemId,
            String productKind,
            String plannedQty,
            String status) {
        public static ProductionGroupMemberResponse from(ProductionOrderResponse order) {
            return new ProductionGroupMemberResponse(
                    order.id(), order.version(), order.productionNo(), order.productNo(),
                    order.groupId(), order.groupNo(), order.buyerOrderId(), order.buyerOrderItemId(),
                    order.productKind(), order.plannedQty(), order.status());
        }
    }

    public record ProductionGroupResponse(
            UUID id, long version, String groupNo, UUID buyerOrderId,
            List<ProductionGroupMemberResponse> members) {
    }

    public record StockPositionResponse(
            UUID id,
            long version,
            UUID productionOrderId,
            UUID buyerOrderItemId,
            UUID customerId,
            String currencyCode,
            String uomCode,
            String orderQty,
            String producedQty,
            String deliveredQty,
            String returnedQty,
            String disposedQty,
            String currentQty,
            String orderBalanceQty,
            Instant updatedAt) {
    }

    public record ProductionFinishResponse(
            ProductionOrderResponse productionOrder,
            StockPositionResponse stockPosition) {
    }
}
