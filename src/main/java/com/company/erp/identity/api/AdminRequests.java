package com.company.erp.identity.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AdminRequests {

    private AdminRequests() {
    }

    public record UserCreateRequest(
            @NotBlank String kind,
            @NotBlank String loginId,
            @NotBlank @Size(min = 12) String temporaryPassword,
            @NotBlank String position,
            @NotBlank String name,
            String groupName,
            String division,
            String sex,
            String phone,
            @Email String email,
            String remark) {
    }

    public record UserProfileUpdateRequest(
            @NotBlank String position,
            @NotBlank String name,
            String groupName,
            String division,
            String sex,
            String phone,
            @Email String email,
            String remark) {
    }

    public record UserStatusUpdateRequest(
            @NotBlank String status,
            @NotBlank String reason) {
    }

    public record UserRolesUpdateRequest(
            @NotNull List<@NotNull UUID> roleIds,
            String reason) {
    }

    public record UserPermissionOverridesUpdateRequest(
            @NotNull List<@Valid PermissionOverrideRequest> overrides,
            String reason) {
    }

    public record PermissionOverrideRequest(
            @NotNull UUID permissionId,
            @NotBlank String effect,
            String reason) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 12) String temporaryPassword,
            String reason) {
    }

    public record RoleRequest(
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String code,
            @NotBlank String name,
            String description,
            @NotNull List<@NotNull UUID> permissionIds) {
    }

    public record RoleUpdateRequest(
            @NotBlank String name,
            String description,
            boolean active,
            @NotNull List<@NotNull UUID> permissionIds,
            String reason) {
    }

    public record IpAllowlistCreateRequest(
            @NotBlank String network,
            @NotBlank String name) {
    }

    public record IpAllowlistUpdateRequest(
            @NotBlank String network,
            @NotBlank String name,
            boolean active) {
    }
}
