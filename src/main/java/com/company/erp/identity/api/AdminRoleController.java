package com.company.erp.identity.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.identity.api.AdminRequests.RoleRequest;
import com.company.erp.identity.api.AdminRequests.RoleUpdateRequest;
import com.company.erp.identity.api.AdminResponses.PermissionPageResponse;
import com.company.erp.identity.api.AdminResponses.RolePageResponse;
import com.company.erp.identity.api.AdminResponses.RoleResponse;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.application.RoleAdministrationService;
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
public class AdminRoleController {

    private final RoleAdministrationService service;

    public AdminRoleController(RoleAdministrationService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ROLES')")
    RolePageResponse listRoles(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) Boolean active) {
        return service.listRoles(page, size, sort, active);
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ROLES')")
    ResponseEntity<RoleResponse> createRole(
            @Valid @RequestBody RoleRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        RoleResponse created = service.createRole(request, principal(authentication), requestId(servletRequest));
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ROLES')")
    RoleResponse getRole(@PathVariable UUID roleId) {
        return service.getRole(roleId);
    }

    @PutMapping("/roles/{roleId}")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ROLES')")
    RoleResponse updateRole(
            @PathVariable UUID roleId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody RoleUpdateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.updateRole(
                roleId,
                AdminQuery.parseIfMatch(ifMatch),
                request,
                principal(authentication),
                requestId(servletRequest));
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ROLES')")
    PermissionPageResponse listPermissions(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String module) {
        return service.listPermissions(page, size, sort, module);
    }

    private static ErpPrincipal principal(JwtAuthenticationToken authentication) {
        return (ErpPrincipal) authentication.getPrincipal();
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(value.length(), 120));
    }
}
