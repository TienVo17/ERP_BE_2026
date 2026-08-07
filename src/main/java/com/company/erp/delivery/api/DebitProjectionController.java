package com.company.erp.delivery.api;

import com.company.erp.delivery.api.DebitModels.DebitNotePageResponse;
import com.company.erp.delivery.application.DebitProjectionService;
import com.company.erp.delivery.infrastructure.DebitProjectionJdbcRepository.Filters;
import com.company.erp.reporting.ReportAdmission;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/debit-notes")
@Validated
public class DebitProjectionController {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final DebitProjectionService service;
    private final ReportAdmission reportAdmission;

    public DebitProjectionController(DebitProjectionService service, ReportAdmission reportAdmission) {
        this.service = service;
        this.reportAdmission = reportAdmission;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DELIVERY:VIEW')")
    ResponseEntity<DebitNotePageResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String deliveryNo,
            @RequestParam(required = false) String sysPoNo,
            @RequestParam(required = false) String buyerPo,
            @RequestParam(required = false) LocalDate deliveryDateFrom,
            @RequestParam(required = false) LocalDate deliveryDateTo,
            @RequestParam(required = false) String currencyCode) {
        Filters filters = new Filters(customerId, deliveryNo, sysPoNo, buyerPo,
                deliveryDateFrom, deliveryDateTo, currencyCode);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(page, size, sort, filters));
    }

    /** Exporting is its own grant: reading the projection does not imply the right to extract it. */
    @GetMapping(value = "/export.xlsx", produces = XLSX_MEDIA_TYPE)
    @PreAuthorize("hasAuthority('DELIVERY:EXPORT')")
    ResponseEntity<byte[]> export(
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String deliveryNo,
            @RequestParam(required = false) String sysPoNo,
            @RequestParam(required = false) String buyerPo,
            @RequestParam(required = false) LocalDate deliveryDateFrom,
            @RequestParam(required = false) LocalDate deliveryDateTo,
            @RequestParam(required = false) String currencyCode) {
        Filters filters = new Filters(customerId, deliveryNo, sysPoNo, buyerPo,
                deliveryDateFrom, deliveryDateTo, currencyCode);
        byte[] workbook = reportAdmission.generate(() -> service.export(sort, filters));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"debit-notes.xlsx\"")
                .cacheControl(CacheControl.noStore())
                .body(workbook);
    }
}
