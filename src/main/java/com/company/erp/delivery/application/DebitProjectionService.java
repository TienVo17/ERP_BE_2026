package com.company.erp.delivery.application;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.delivery.api.DebitModels.DebitNotePageResponse;
import com.company.erp.delivery.api.DebitModels.DebitNoteResponse;
import com.company.erp.delivery.api.DebitModels.PageMetadata;
import com.company.erp.delivery.infrastructure.DebitProjectionJdbcRepository;
import com.company.erp.delivery.infrastructure.DebitProjectionJdbcRepository.DebitRow;
import com.company.erp.delivery.infrastructure.DebitProjectionJdbcRepository.Filters;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.reporting.XlsxWorkbookWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DebitProjectionService {

    /** An export beyond this size is refused rather than streamed; it is an operator mistake. */
    private static final int EXPORT_ROW_LIMIT = 50_000;
    private static final List<String> EXPORT_HEADERS = List.of(
            "Debit Reference", "Delivery No", "Customer", "Delivery Date", "SYS PO", "Buyer PO",
            "Style No", "Name", "Size", "Color", "UOM", "Quantity", "Unit Price", "Amount", "Currency");

    private final DebitProjectionJdbcRepository repository;
    private final XlsxWorkbookWriter workbookWriter;

    public DebitProjectionService(DebitProjectionJdbcRepository repository,
            XlsxWorkbookWriter workbookWriter) {
        this.repository = repository;
        this.workbookWriter = workbookWriter;
    }

    public DebitNotePageResponse list(int page, int size, List<String> sort, Filters filters) {
        AdminQuery.validatePage(page, size);
        List<String> normalized = repository.sort(sort);
        long total = repository.count(filters);
        List<DebitNoteResponse> items = repository
                .rows(filters, normalized, size, Math.multiplyExact((long) page, size))
                .stream().map(DebitProjectionService::response).toList();
        return new DebitNotePageResponse(items,
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters(
                        "customerId", filters.customerId(), "deliveryNo", filters.deliveryNo(),
                        "sysPoNo", filters.sysPoNo(), "buyerPo", filters.buyerPo(),
                        "deliveryDateFrom", filters.deliveryDateFrom(),
                        "deliveryDateTo", filters.deliveryDateTo(),
                        "currencyCode", filters.currencyCode()),
                normalized);
    }

    /** Same filters, same sort, same query as the list; only the rendering differs. */
    public byte[] export(List<String> sort, Filters filters) {
        List<String> normalized = repository.sort(sort);
        long total = repository.count(filters);
        if (total > EXPORT_ROW_LIMIT) {
            throw new ApiException(ApiErrorCode.REPORT_LIMIT_EXCEEDED,
                    "The export exceeds the supported row limit. Narrow the filters and retry.");
        }
        List<List<Object>> rows = new ArrayList<>();
        for (DebitRow row : repository.rows(filters, normalized, null, null)) {
            rows.add(List.of(
                    value(row.debitReference()), value(row.deliveryNo()), value(row.customerName()),
                    row.deliveryDate(), value(row.sysPoNo()), value(row.buyerPo()),
                    value(row.styleNo()), value(row.name()), value(row.size()), value(row.color()),
                    value(row.uomCode()), row.totalQty(), row.unitPrice(), row.amount(),
                    value(row.currencyCode())));
        }
        return workbookWriter.write(EXPORT_HEADERS, rows);
    }

    private static Object value(String text) {
        return text == null ? "" : text;
    }

    private static DebitNoteResponse response(DebitRow row) {
        return new DebitNoteResponse(
                row.deliveryNoteItemId(), row.debitReference(), row.deliveryNoteId(), row.deliveryNo(),
                row.deliveryDate(), row.customerId(), row.customerName(), row.lineNo(), row.sysPoNo(),
                row.buyerPo(), row.productKind(), row.styleNo(), row.name(), row.size(), row.color(),
                row.uomCode(), quantity(row.totalQty()), price(row.unitPrice()), amount(row.amount()),
                row.currencyCode());
    }

    private static String quantity(BigDecimal value) { return value.setScale(4).toPlainString(); }
    private static String price(BigDecimal value) { return value.setScale(6).toPlainString(); }
    private static String amount(BigDecimal value) { return value.setScale(2).toPlainString(); }
}
