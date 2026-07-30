package com.company.erp.masterdata.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateRequest;
import com.company.erp.masterdata.api.MasterDataModels.ExchangeRateResponse;
import com.company.erp.masterdata.api.MasterDataModels.PageResponse;
import com.company.erp.masterdata.api.MasterDataModels.UomRequest;
import com.company.erp.masterdata.api.MasterDataModels.UomResponse;
import com.company.erp.masterdata.application.MasterDataSupport;
import com.company.erp.masterdata.application.ReferenceMasterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/master-data")
@Validated
public class ReferenceMasterController {

    private final ReferenceMasterService service;

    public ReferenceMasterController(ReferenceMasterService service) {
        this.service = service;
    }

    @GetMapping("/currencies")
    @PreAuthorize("hasAuthority('ETC:VIEW')")
    PageResponse<MasterDataModels.CurrencyResponse> currencies(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) Boolean active) {
        return service.currencies(page, size, sort, active);
    }

    @GetMapping("/uoms")
    @PreAuthorize("hasAuthority('ETC:VIEW')")
    PageResponse<UomResponse> uoms(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String code) {
        return service.uoms(page, size, sort, status, code);
    }

    @PostMapping("/uoms")
    @PreAuthorize("hasAuthority('ETC:CREATE')")
    ResponseEntity<UomResponse> createUom(@Valid @RequestBody UomRequest request,
            JwtAuthenticationToken authentication, HttpServletRequest servletRequest) {
        return ResponseEntity.status(201).body(service.createUom(request,
                MasterDataSupport.principal(authentication), MasterDataSupport.requestId(servletRequest)));
    }

    @GetMapping("/uoms/{id}")
    @PreAuthorize("hasAuthority('ETC:VIEW')")
    UomResponse uom(@PathVariable UUID id) {
        return service.uom(id);
    }

    @PutMapping("/uoms/{id}")
    @PreAuthorize("hasAuthority('ETC:UPDATE')")
    UomResponse updateUom(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UomRequest request, JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.updateUom(id, MasterDataSupport.version(ifMatch), request,
                MasterDataSupport.principal(authentication), MasterDataSupport.requestId(servletRequest));
    }

    @PostMapping("/uoms/{id}/archive")
    @PreAuthorize("hasAuthority('ETC:ARCHIVE')")
    UomResponse archiveUom(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            JwtAuthenticationToken authentication, HttpServletRequest servletRequest) {
        return service.archiveUom(id, MasterDataSupport.version(ifMatch), MasterDataSupport.principal(authentication),
                MasterDataSupport.requestId(servletRequest));
    }

    @GetMapping("/exchange-rates")
    @PreAuthorize("hasAuthority('ETC:VIEW')")
    PageResponse<ExchangeRateResponse> rates(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String status) {
        return service.rates(page, size, sort, month, status);
    }

    @PostMapping("/exchange-rates")
    @PreAuthorize("hasAuthority('ETC:CREATE')")
    ResponseEntity<ExchangeRateResponse> createRate(@Valid @RequestBody ExchangeRateRequest request,
            JwtAuthenticationToken authentication, HttpServletRequest servletRequest) {
        return ResponseEntity.status(201).body(service.createRate(request,
                MasterDataSupport.principal(authentication), MasterDataSupport.requestId(servletRequest)));
    }

    @GetMapping("/exchange-rates/{id}")
    @PreAuthorize("hasAuthority('ETC:VIEW')")
    ExchangeRateResponse rate(@PathVariable UUID id) {
        return service.rate(id);
    }

    @PutMapping("/exchange-rates/{id}")
    @PreAuthorize("hasAuthority('ETC:UPDATE')")
    ExchangeRateResponse updateRate(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ExchangeRateRequest request, JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.updateRate(id, MasterDataSupport.version(ifMatch), request,
                MasterDataSupport.principal(authentication), MasterDataSupport.requestId(servletRequest));
    }

    @PostMapping("/exchange-rates/{id}/archive")
    @PreAuthorize("hasAuthority('ETC:ARCHIVE')")
    ExchangeRateResponse archiveRate(@PathVariable UUID id, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            JwtAuthenticationToken authentication, HttpServletRequest servletRequest) {
        return service.archiveRate(id, MasterDataSupport.version(ifMatch), MasterDataSupport.principal(authentication),
                MasterDataSupport.requestId(servletRequest));
    }
}
