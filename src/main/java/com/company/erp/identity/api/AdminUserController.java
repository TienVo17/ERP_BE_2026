package com.company.erp.identity.api;

import java.util.List;
import java.util.UUID;

import com.company.erp.identity.api.AdminRequests.ResetPasswordRequest;
import com.company.erp.identity.api.AdminRequests.UserCreateRequest;
import com.company.erp.identity.api.AdminRequests.UserPermissionOverridesUpdateRequest;
import com.company.erp.identity.api.AdminRequests.UserProfileUpdateRequest;
import com.company.erp.identity.api.AdminRequests.UserRolesUpdateRequest;
import com.company.erp.identity.api.AdminRequests.UserStatusUpdateRequest;
import com.company.erp.identity.api.AdminResponses.UserPageResponse;
import com.company.erp.identity.api.AdminResponses.UserResponse;
import com.company.erp.identity.application.AdminQuery;
import com.company.erp.identity.application.UserAdministrationService;
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
@RequestMapping("/api/v1/admin/users")
@Validated
public class AdminUserController {

    private final UserAdministrationService service;

    public AdminUserController(UserAdministrationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_USERS')")
    UserPageResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String loginId,
            @RequestParam(required = false) String name) {
        return service.list(page, size, sort, status, kind, loginId, name);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_USERS')")
    ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        UserResponse created = service.create(request, principal(authentication), requestId(servletRequest));
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_USERS')")
    UserResponse get(@PathVariable UUID userId) {
        return service.get(userId);
    }

    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_USERS')")
    UserResponse updateProfile(
            @PathVariable UUID userId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UserProfileUpdateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.updateProfile(
                userId, AdminQuery.parseIfMatch(ifMatch), request,
                principal(authentication), requestId(servletRequest));
    }

    @PutMapping("/{userId}/status")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_USERS')")
    UserResponse updateStatus(
            @PathVariable UUID userId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UserStatusUpdateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.updateStatus(
                userId, AdminQuery.parseIfMatch(ifMatch), request,
                principal(authentication), requestId(servletRequest));
    }

    @PutMapping("/{userId}/roles")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ROLES')")
    UserResponse replaceRoles(
            @PathVariable UUID userId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UserRolesUpdateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.replaceRoles(
                userId, AdminQuery.parseIfMatch(ifMatch), request,
                principal(authentication), requestId(servletRequest));
    }

    @PutMapping("/{userId}/permission-overrides")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_ROLES')")
    UserResponse replaceOverrides(
            @PathVariable UUID userId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody UserPermissionOverridesUpdateRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        return service.replaceOverrides(
                userId, AdminQuery.parseIfMatch(ifMatch), request,
                principal(authentication), requestId(servletRequest));
    }

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("hasAuthority('ADMIN:MANAGE_USERS')")
    ResponseEntity<Void> resetPassword(
            @PathVariable UUID userId,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @Valid @RequestBody ResetPasswordRequest request,
            JwtAuthenticationToken authentication,
            HttpServletRequest servletRequest) {
        service.resetPassword(
                userId,
                AdminQuery.parseIfMatch(ifMatch),
                request.temporaryPassword(),
                request.reason(),
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
