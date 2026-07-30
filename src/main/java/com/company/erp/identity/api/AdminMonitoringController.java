package com.company.erp.identity.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.identity.api.AdminRequests.IpAllowlistCreateRequest;
import com.company.erp.identity.api.AdminRequests.IpAllowlistUpdateRequest;
import com.company.erp.identity.api.AdminResponses.IpAllowlistEntryResponse;
import com.company.erp.identity.api.AdminResponses.IpAllowlistPageResponse;
import com.company.erp.identity.api.AdminResponses.LoginEventPageResponse;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.application.MonitoringAdministrationService;
import com.company.erp.identity.security.ErpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/admin")
@Validated
public class AdminMonitoringController {

    private final MonitoringAdministrationService service;

    public AdminMonitoringController(MonitoringAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/login-events")
    @PreAuthorize("hasAuthority('ADMIN:VIEW_AUDIT')")
    LoginEventPageResponse loginEvents(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String loginId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return service.loginEvents(page, size, sort, userId, loginId, outcome, from, to);
    }

    @GetMapping("/ip-allowlist")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ALLOWLIST')")
    IpAllowlistPageResponse allowlist(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) Boolean active) {
        return service.allowlist(page, size, sort, active);
    }

    @PostMapping("/ip-allowlist")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ALLOWLIST')")
    ResponseEntity<IpAllowlistEntryResponse> createAllowlist(
            @Valid @RequestBody IpAllowlistCreateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        IpAllowlistEntryResponse created = service.createAllowlist(
                request.network(), request.name(), principal(authentication), requestId(servletRequest));
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/ip-allowlist/{entryId}")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ALLOWLIST')")
    IpAllowlistEntryResponse getAllowlist(@PathVariable UUID entryId) {
        return service.getAllowlist(entryId);
    }

    @PutMapping("/ip-allowlist/{entryId}")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ALLOWLIST')")
    IpAllowlistEntryResponse updateAllowlist(
            @PathVariable UUID entryId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody IpAllowlistUpdateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.updateAllowlist(
                entryId,
                AdminQuery.parseIfMatch(ifMatch),
                request.network(),
                request.name(),
                request.active(),
                principal(authentication),
                requestId(servletRequest));
    }

    @DeleteMapping("/ip-allowlist/{entryId}")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ALLOWLIST')")
    ResponseEntity<Void> deleteAllowlist(
            @PathVariable UUID entryId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        service.deleteAllowlist(
                entryId,
                AdminQuery.parseIfMatch(ifMatch),
                principal(authentication),
                requestId(servletRequest));
        return ResponseEntity.noContent().build();
    }

    private static ErpPrincipal principal(JwtAuthenticationToken authentication) {
        return (ErpPrincipal) authentication.getPrincipal();
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(value.length(), 120));
    }
}
