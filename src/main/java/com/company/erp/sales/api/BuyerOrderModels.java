package com.company.erp.sales.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BuyerOrderModels {

    private BuyerOrderModels() {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "isCustom", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = StandardBuyerOrderItemRequest.class, name = "false"),
            @JsonSubTypes.Type(value = CustomBuyerOrderItemRequest.class, name = "true")
    })
    public sealed interface BuyerOrderItemRequest
            permits StandardBuyerOrderItemRequest, CustomBuyerOrderItemRequest {
        boolean isCustom();
        String orderQty();
        String unitPrice();
        String remark();
    }

    public record StandardBuyerOrderItemRequest(
            boolean isCustom,
            @NotNull UUID finishedGoodId,
            @NotBlank @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String orderQty,
            @NotBlank @Pattern(regexp = "^\\d{1,12}(\\.\\d{1,6})?$") String unitPrice,
            @Size(max = 200) String remark) implements BuyerOrderItemRequest {
    }

    public record CustomBuyerOrderItemRequest(
            boolean isCustom,
            @NotBlank @Pattern(regexp = "^(PRINT|WOVEN)$") String productKind,
            @NotBlank @Size(max = 120) String styleNo,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 120) String size,
            @Size(max = 120) String color,
            @NotNull UUID uomId,
            @NotBlank @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String orderQty,
            @NotBlank @Pattern(regexp = "^\\d{1,12}(\\.\\d{1,6})?$") String unitPrice,
            @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currencyCode,
            @Size(max = 200) String remark) implements BuyerOrderItemRequest {
    }

    public record BuyerOrderCreateRequest(
            @NotBlank @Size(max = 80) String orderType,
            @NotNull UUID customerId,
            UUID customerContactId,
            @NotBlank @Pattern(regexp = "^(MASTER|CUSTOM)$") String picSource,
            @NotBlank @Size(max = 200) String picName,
            @NotBlank @Size(max = 120) String buyerPo,
            @NotNull LocalDate poDate,
            @NotNull LocalDate deliveryDate,
            @NotEmpty @Size(max = 100) List<@Valid BuyerOrderItemRequest> items) {
    }

    public record BuyerOrderUpdateRequest(
            @NotBlank @Size(max = 80) String orderType,
            @NotNull UUID customerId,
            UUID customerContactId,
            @NotBlank @Pattern(regexp = "^(MASTER|CUSTOM)$") String picSource,
            @NotBlank @Size(max = 200) String picName,
            @NotBlank @Size(max = 120) String buyerPo,
            @NotNull LocalDate poDate,
            @NotNull LocalDate deliveryDate,
            @NotEmpty @Size(max = 100) List<@Valid BuyerOrderItemRequest> items) {
    }

    public record BuyerOrderCopyRequest(
            LocalDate poDate, LocalDate deliveryDate,
            @Size(max = 120) String buyerPo, @Size(max = 200) String reason) {
    }

    public record BuyerOrderCommandRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record BuyerOrderItemResponse(
            UUID id, int lineNo, int revision, boolean activeRevision, String status,
            boolean isCustom, UUID finishedGoodId, String productKind, String styleNo,
            String name, String size, String color, UUID uomId, String uomCode,
            String orderQty, String useStockQty, String productionQty, String unitPrice,
            String currencyCode, String amount, String remark) {
    }

    public record BuyerOrderResponse(
            UUID id, long version, String sysPoNo, int revision, String status,
            String orderType, UUID customerId, String customerName, String customerShortName,
            UUID customerContactId, String picSource, String picName, String buyerPo,
            LocalDate poDate, LocalDate deliveryDate, List<BuyerOrderItemResponse> items,
            Instant createdAt, Instant updatedAt) {
    }

    public record ProductionEventResponse(
            UUID id, String eventType, UUID actorUserId, String reason, Instant occurredAt) {
    }

    public record ProductionConfigurationResponse(
            String productKind, String materialSource, String orderKind,
            String bim, String tenDia, String matDo, String pick,
            String xNgangMm, String yDocMm, String haiDaMm,
            String logoXMm, String logoYMm, String hoPercent,
            List<String> weaveTypes, List<Object> processes,
            List<Object> yarnLines, String remark) {
        public static ProductionConfigurationResponse empty(String productKind) {
            return new ProductionConfigurationResponse(
                    productKind, null, null, null, null, null, null,
                    null, null, null, null, null, null,
                    List.of(), List.of(), List.of(), null);
        }
    }

    public record ProductionOrderResponse(
            UUID id, long version, String productionNo, String productNo, String qrValue,
            UUID groupId, String groupNo, UUID buyerOrderId, UUID buyerOrderItemId,
            String productKind, String plannedQty, String producedQty, String status,
            ProductionConfigurationResponse configuration, List<ProductionEventResponse> events,
            Instant finishedAt, Instant createdAt, Instant updatedAt) {
    }

    public record BuyerOrderConfirmResponse(
            BuyerOrderResponse buyerOrder, List<ProductionOrderResponse> productionOrders) {
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record BuyerOrderPageResponse(
            List<BuyerOrderResponse> items, PageMetadata page,
            Map<String, Object> filters, List<String> sort) {
    }
}
