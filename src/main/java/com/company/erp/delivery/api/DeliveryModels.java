package com.company.erp.delivery.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeliveryModels {

    private DeliveryModels() {
    }

    public record DeliveryNoteItemRequest(
            @NotNull UUID stockPositionId,
            @NotNull @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String deliveryQty,
            @NotNull @Pattern(regexp = "^\\d{1,12}(\\.\\d{1,6})?$") String unitPrice) {
    }

    public record DeliveryNoteCreateRequest(
            @NotNull LocalDate deliveryDate,
            @NotNull @Pattern(regexp = "^(100(\\.0{1,4})?|([0-9]|[1-9][0-9])(\\.\\d{1,4})?)$") String vatPercent,
            @Size(max = 200) String remark,
            @NotEmpty @Size(min = 1, max = 100) List<@Valid DeliveryNoteItemRequest> items) {
    }

    public record DeliveryNoteUpdateRequest(
            @NotNull LocalDate deliveryDate,
            @NotNull @Pattern(regexp = "^(100(\\.0{1,4})?|([0-9]|[1-9][0-9])(\\.\\d{1,4})?)$") String vatPercent,
            @Size(max = 200) String remark,
            @NotEmpty @Size(min = 1, max = 100) List<@Valid DeliveryNoteItemRequest> items) {
    }

    public record DeliveryCommandRequest(
            @NotNull @Size(min = 1, max = 200) String reason) {
    }

    /** Only the fields needed to compose a draft; this projection never confers DELIVERY:VIEW. */
    public record DeliverySourceResponse(
            UUID stockPositionId,
            UUID buyerOrderId,
            UUID buyerOrderItemId,
            UUID productionOrderId,
            UUID customerId,
            String currencyCode,
            String availableQty,
            String uomCode,
            String sysPoNo,
            String buyerPo,
            String productKind,
            String styleNo,
            String name,
            String size,
            String color) {
    }

    public record DeliveryNoteItemResponse(
            UUID id,
            int lineNo,
            UUID stockPositionId,
            UUID buyerOrderId,
            UUID buyerOrderItemId,
            UUID productionOrderId,
            String sysPoNo,
            String buyerPo,
            String productKind,
            String styleNo,
            String name,
            String size,
            String color,
            String uomCode,
            String currencyCode,
            String deliveryQty,
            String unitPrice,
            String amount) {
    }

    public record DeliveryEventResponse(
            UUID id, String eventType, UUID actorUserId, String reason, Instant occurredAt) {
    }

    public record DeliveryNoteResponse(
            UUID id,
            long version,
            String deliveryNo,
            UUID customerId,
            String customerName,
            String customerAddress,
            LocalDate deliveryDate,
            String currencyCode,
            String vndUsdRate,
            String wonUsdRate,
            String vatPercent,
            String totalQty,
            String totalAmount,
            String status,
            UUID replacesDeliveryId,
            UUID replacementDeliveryId,
            String remark,
            List<DeliveryNoteItemResponse> items,
            List<DeliveryEventResponse> events,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record DeliveryReverseResponse(
            DeliveryNoteResponse reversedDelivery,
            DeliveryNoteResponse replacementDraft) {
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record DeliverySourcePageResponse(
            List<DeliverySourceResponse> items, PageMetadata page,
            Map<String, Object> filters, List<String> sort) {
    }

    public record DeliveryNotePageResponse(
            List<DeliveryNoteResponse> items, PageMetadata page,
            Map<String, Object> filters, List<String> sort) {
    }

    public record DeliveryEventPageResponse(
            List<DeliveryEventResponse> items, PageMetadata page,
            Map<String, Object> filters, List<String> sort) {
    }
}
