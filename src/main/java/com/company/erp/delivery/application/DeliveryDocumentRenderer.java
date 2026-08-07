package com.company.erp.delivery.application;

import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteItemResponse;
import com.company.erp.delivery.api.DeliveryModels.DeliveryNoteResponse;
import com.company.erp.reporting.PdfDocumentWriter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Renders the official document from committed snapshots, never from current master data. */
@Component
public class DeliveryDocumentRenderer {

    private final PdfDocumentWriter writer;

    public DeliveryDocumentRenderer(PdfDocumentWriter writer) {
        this.writer = writer;
    }

    public byte[] render(DeliveryNoteResponse delivery) {
        List<String> lines = new ArrayList<>();
        lines.add("Status: " + delivery.status());
        lines.add("Customer: " + value(delivery.customerName()));
        lines.add("Address: " + value(delivery.customerAddress()));
        lines.add("Delivery date: " + delivery.deliveryDate());
        lines.add("Currency: " + delivery.currencyCode());
        if (delivery.vndUsdRate() != null) {
            lines.add("VND/USD rate: " + delivery.vndUsdRate());
        }
        if (delivery.wonUsdRate() != null) {
            lines.add("WON/USD rate: " + delivery.wonUsdRate());
        }
        lines.add("VAT percent: " + delivery.vatPercent());
        if (delivery.replacesDeliveryId() != null) {
            lines.add("Replaces delivery: " + delivery.replacesDeliveryId());
        }
        if (delivery.replacementDeliveryId() != null) {
            lines.add("Replaced by delivery: " + delivery.replacementDeliveryId());
        }
        lines.add("");
        for (DeliveryNoteItemResponse item : delivery.items()) {
            lines.add("Line " + item.lineNo() + ": " + value(item.styleNo()) + " "
                    + value(item.name()) + " " + item.deliveryQty() + " " + value(item.uomCode())
                    + " @ " + item.unitPrice() + " = " + item.amount());
        }
        lines.add("");
        lines.add("Total quantity: " + delivery.totalQty());
        lines.add("Total amount: " + delivery.totalAmount() + " " + delivery.currencyCode());
        if (delivery.remark() != null) {
            lines.add("Remark: " + delivery.remark());
        }
        return writer.write("Delivery " + value(delivery.deliveryNo()), lines);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
