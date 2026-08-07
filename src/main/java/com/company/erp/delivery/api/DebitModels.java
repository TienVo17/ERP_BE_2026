package com.company.erp.delivery.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DebitModels {

    private DebitModels() {
    }

    /** A read-only projection of POSTED Delivery lines; it has no aggregate of its own. */
    public record DebitNoteResponse(
            UUID deliveryNoteItemId,
            String debitReference,
            UUID deliveryNoteId,
            String deliveryNo,
            LocalDate deliveryDate,
            UUID customerId,
            String customerName,
            int lineNo,
            String sysPoNo,
            String buyerPo,
            String productKind,
            String styleNo,
            String name,
            String size,
            String color,
            String uomCode,
            String totalQty,
            String unitPrice,
            String amount,
            String currencyCode) {
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record DebitNotePageResponse(
            List<DebitNoteResponse> items, PageMetadata page,
            Map<String, Object> filters, List<String> sort) {
    }
}
