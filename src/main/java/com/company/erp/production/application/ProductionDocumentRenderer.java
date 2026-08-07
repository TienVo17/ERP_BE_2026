package com.company.erp.production.application;

import com.company.erp.production.api.ProductionModels.ProductionOrderResponse;
import com.company.erp.reporting.PdfDocumentWriter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProductionDocumentRenderer {

    private final PdfDocumentWriter writer;

    public ProductionDocumentRenderer(PdfDocumentWriter writer) {
        this.writer = writer;
    }

    public byte[] render(ProductionOrderResponse order) {
        List<String> lines = new ArrayList<>();
        lines.add("Status: " + order.status());
        lines.add("Product number: " + order.productNo());
        lines.add("Product kind: " + order.productKind());
        lines.add("Planned quantity: " + order.plannedQty());
        lines.add("Produced quantity: " + (order.producedQty() == null ? "" : order.producedQty()));
        lines.add("Configuration:");
        if (order.configuration().materialSource() != null) {
            lines.add("Material source: " + order.configuration().materialSource());
        }
        if (order.configuration().orderKind() != null) {
            lines.add("Order kind: " + order.configuration().orderKind());
        }
        if (order.configuration().bim() != null) {
            lines.add("BIM: " + order.configuration().bim());
        }
        order.configuration().processes().forEach(process -> lines.add(
                "Process " + process.sequenceNo() + ": " + process.processId()
                        + (process.speed() == null ? "" : " @ " + process.speed())));
        for (int index = 0; index < order.configuration().yarnLines().size(); index++) {
            var yarn = order.configuration().yarnLines().get(index);
            lines.add("Yarn " + (index + 1) + ": " + value(yarn.yarn()) + " "
                    + value(yarn.yarnCode()) + " " + value(yarn.denier()));
        }
        if (order.configuration().remark() != null) {
            lines.add("Remark: " + order.configuration().remark());
        }
        return writer.write("Production " + order.productionNo(), lines);
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }
}
