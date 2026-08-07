package com.company.erp.inventory.api;

import com.company.erp.production.api.ProductionModels.StockPositionResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StockModels {

    private StockModels() {
    }

    public record StockReturnRequest(
            @NotNull UUID deliveryNoteItemId,
            @NotBlank @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String quantity,
            @NotNull LocalDate businessDate,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record StockDisposalRequest(
            @NotBlank @Pattern(regexp = "^(?=.*[1-9])\\d{1,14}(\\.\\d{1,4})?$") String quantity,
            @NotNull LocalDate businessDate,
            @NotBlank @Size(max = 200) String reason) {
    }

    public record StockMovementResponse(
            UUID id,
            String movementType,
            String quantitySigned,
            String balanceAfter,
            LocalDate businessDate,
            String sourceType,
            UUID sourceId,
            UUID sourceItemId,
            String reason,
            Instant occurredAt) {
    }

    public record EligibleReturnSourceResponse(
            UUID deliveryNoteItemId,
            String deliveryNo,
            LocalDate deliveryDate,
            String deliveredQty,
            String netReturnableQty,
            String uomCode) {
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record StockPositionPageResponse(
            List<StockPositionResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }

    public record StockMovementPageResponse(
            List<StockMovementResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }

    public record EligibleReturnSourcePageResponse(
            List<EligibleReturnSourceResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }

    public record StockCommandResponse(
            StockPositionResponse stockPosition,
            StockMovementResponse movement) {
    }
}
