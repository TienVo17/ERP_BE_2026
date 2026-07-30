package com.company.erp.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.company.erp.identity.api.AuthResponses.PageMetadata;

public final class AdminResponses {

    private AdminResponses() {
    }

    public record PermissionOverrideResponse(
            UUID permissionId,
            String module,
            String action,
            String effect) {
    }

    public record UserResponse(
            UUID id,
            long version,
            String status,
            String kind,
            String loginId,
            String name,
            String position,
            String groupName,
            String division,
            String sex,
            String phone,
            String email,
            String remark,
            List<UUID> roleIds,
            List<PermissionOverrideResponse> permissionOverrides,
            boolean mustChangePassword,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record RoleResponse(
            UUID id,
            long version,
            String code,
            String name,
            String description,
            boolean active,
            List<UUID> permissionIds,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record LoginEventResponse(
            UUID id,
            UUID userId,
            String loginId,
            String userName,
            String outcome,
            String clientIp,
            String ipName,
            String userAgent,
            Instant occurredAt) {
    }

    public record IpAllowlistEntryResponse(
            UUID id,
            long version,
            String network,
            String name,
            boolean active,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record UserPageResponse(
            List<UserResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }

    public record RolePageResponse(
            List<RoleResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }

    public record PermissionPageResponse(
            List<AuthResponses.PermissionResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }

    public record LoginEventPageResponse(
            List<LoginEventResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }

    public record IpAllowlistPageResponse(
            List<IpAllowlistEntryResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort,
            boolean enforced) {
    }
}
