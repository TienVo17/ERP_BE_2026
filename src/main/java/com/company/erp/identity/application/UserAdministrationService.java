package com.company.erp.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.api.AdminRequests.UserCreateRequest;
import com.company.erp.identity.api.AdminRequests.UserPermissionOverridesUpdateRequest;
import com.company.erp.identity.api.AdminRequests.UserProfileUpdateRequest;
import com.company.erp.identity.api.AdminRequests.UserRolesUpdateRequest;
import com.company.erp.identity.api.AdminRequests.UserStatusUpdateRequest;
import com.company.erp.identity.api.AdminResponses.UserPageResponse;
import com.company.erp.identity.api.AdminResponses.UserResponse;
import com.company.erp.identity.api.AuthResponses.PageMetadata;
import com.company.erp.identity.infrastructure.AdminUserJdbcRepository;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.ErpPrincipal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdministrationService {

    private static final Set<String> KINDS = Set.of("USER", "STAFF");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED", "ARCHIVED");
    private static final Set<String> EFFECTS = Set.of("ALLOW", "DENY");

    private final AdminUserJdbcRepository repository;
    private final IdentityJdbcRepository identityRepository;
    private final PasswordService passwordService;
    private final AuditEventWriter auditWriter;
    private final Clock clock;

    @Autowired
    public UserAdministrationService(
            AdminUserJdbcRepository repository,
            IdentityJdbcRepository identityRepository,
            PasswordService passwordService,
            AuditEventWriter auditWriter) {
        this(repository, identityRepository, passwordService, auditWriter, Clock.systemUTC());
    }

    UserAdministrationService(
            AdminUserJdbcRepository repository,
            IdentityJdbcRepository identityRepository,
            PasswordService passwordService,
            AuditEventWriter auditWriter,
            Clock clock) {
        this.repository = repository;
        this.identityRepository = identityRepository;
        this.passwordService = passwordService;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    public UserPageResponse list(
            int page,
            int size,
            List<String> requestedSort,
            String status,
            String kind,
            String loginId,
            String name) {
        AdminQuery.validatePage(page, size);
        String normalizedStatus = AdminQuery.enumValue(status, STATUSES, "status");
        String normalizedKind = AdminQuery.enumValue(kind, KINDS, "kind");
        List<String> sort = repository.normalizeSort(requestedSort);
        long total = repository.count(normalizedStatus, normalizedKind, loginId, name);
        return new UserPageResponse(
                repository.list(page, size, normalizedStatus, normalizedKind, loginId, name, sort),
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters(
                        "status", normalizedStatus,
                        "kind", normalizedKind,
                        "loginId", loginId == null ? null : loginId.trim(),
                        "name", name == null ? null : name.trim()),
                sort);
    }

    public UserResponse get(UUID userId) {
        return repository.find(userId)
                .orElseThrow(() -> new ResourceNotFoundException("app user", userId.toString()));
    }

    @Transactional
    public UserResponse create(UserCreateRequest request, ErpPrincipal actor, String requestId) {
        String kind = AdminQuery.enumValue(request.kind(), KINDS, "kind");
        validateSex(request.sex());
        UserCreateRequest normalized = new UserCreateRequest(
                kind,
                request.loginId().trim(),
                request.temporaryPassword(),
                request.position(),
                request.name(),
                request.groupName(),
                request.division(),
                request.sex(),
                request.phone(),
                request.email(),
                request.remark());
        UUID id = repository.create(normalized, passwordService.encode(request.temporaryPassword()), actor.user().id());
        UserResponse created = get(id);
        auditWriter.write(
                actor.user().id(),
                "USER_CREATED",
                "APP_USER",
                id,
                requestId,
                null,
                null,
                summary(created));
        return created;
    }

    @Transactional
    public UserResponse updateProfile(
            UUID userId,
            long version,
            UserProfileUpdateRequest request,
            ErpPrincipal actor,
            String requestId) {
        validateSex(request.sex());
        UserResponse before = get(userId);
        requireUpdated(repository.updateProfile(userId, version, request, actor.user().id()));
        UserResponse after = get(userId);
        auditWriter.write(
                actor.user().id(), "USER_PROFILE_UPDATED", "APP_USER", userId,
                requestId, null, summary(before), summary(after));
        return after;
    }

    @Transactional
    public UserResponse updateStatus(
            UUID userId,
            long version,
            UserStatusUpdateRequest request,
            ErpPrincipal actor,
            String requestId) {
        String status = AdminQuery.enumValue(request.status(), STATUSES, "status");
        UserResponse before = get(userId);
        repository.lockRecoveryState();
        requireUpdated(repository.updateStatus(userId, version, status, actor.user().id()));
        enforceRecoveryQuorum();
        if (!"ACTIVE".equals(status)) {
            identityRepository.revokeAllSessions(userId, clock.instant(), "USER_" + status);
        }
        UserResponse after = get(userId);
        auditWriter.write(
                actor.user().id(), "USER_STATUS_CHANGED", "APP_USER", userId,
                requestId, request.reason(), summary(before), summary(after));
        return after;
    }

    @Transactional
    public UserResponse replaceRoles(
            UUID userId,
            long version,
            UserRolesUpdateRequest request,
            ErpPrincipal actor,
            String requestId) {
        UserResponse before = get(userId);
        if (!repository.rolesExist(request.roleIds())) {
            throw invalid("Every roleId must reference an existing role.");
        }
        repository.lockRecoveryState();
        requireUpdated(repository.touchVersion(userId, version, actor.user().id()));
        repository.replaceRoles(userId, request.roleIds(), actor.user().id());
        enforceRecoveryQuorum();
        identityRepository.revokeAllSessions(userId, clock.instant(), "ROLES_CHANGED");
        UserResponse after = get(userId);
        auditWriter.write(
                actor.user().id(), "USER_ROLES_REPLACED", "APP_USER", userId,
                requestId, request.reason(), summary(before), summary(after));
        return after;
    }

    @Transactional
    public UserResponse replaceOverrides(
            UUID userId,
            long version,
            UserPermissionOverridesUpdateRequest request,
            ErpPrincipal actor,
            String requestId) {
        UserResponse before = get(userId);
        List<UUID> permissionIds = request.overrides().stream().map(item -> item.permissionId()).toList();
        if (permissionIds.stream().distinct().count() != permissionIds.size()
                || !repository.activePermissionsExist(permissionIds)) {
            throw invalid("Overrides must reference unique active seeded permissions.");
        }
        request.overrides().forEach(item -> AdminQuery.enumValue(item.effect(), EFFECTS, "effect"));
        repository.lockRecoveryState();
        requireUpdated(repository.touchVersion(userId, version, actor.user().id()));
        repository.replaceOverrides(userId, request.overrides().stream()
                .map(item -> new com.company.erp.identity.api.AdminRequests.PermissionOverrideRequest(
                        item.permissionId(), item.effect().toUpperCase(Locale.ROOT), item.reason()))
                .toList(), actor.user().id());
        enforceRecoveryQuorum();
        identityRepository.revokeAllSessions(userId, clock.instant(), "PERMISSIONS_CHANGED");
        UserResponse after = get(userId);
        auditWriter.write(
                actor.user().id(), "USER_PERMISSION_OVERRIDES_REPLACED", "APP_USER", userId,
                requestId, request.reason(), summary(before), summary(after));
        return after;
    }

    @Transactional
    public void resetPassword(
            UUID userId,
            long version,
            String temporaryPassword,
            String reason,
            ErpPrincipal actor,
            String requestId) {
        UserResponse before = get(userId);
        repository.lockRecoveryState();
        requireUpdated(repository.resetPassword(
                userId,
                version,
                passwordService.encode(temporaryPassword),
                clock.instant(),
                actor.user().id()));
        enforceRecoveryQuorum();
        identityRepository.revokeAllSessions(userId, clock.instant(), "PASSWORD_RESET");
        UserResponse after = get(userId);
        auditWriter.write(
                actor.user().id(), "USER_PASSWORD_RESET", "APP_USER", userId,
                requestId, reason, summary(before), summary(after));
    }

    public void enforceRecoveryQuorum() {
        if (repository.recoveryCapableCount() < 2) {
            throw new ApiException(
                    ApiErrorCode.RECOVERY_ADMIN_REQUIRED,
                    "At least two independent recovery-capable administrators must remain.");
        }
    }

    private static Map<String, Object> summary(UserResponse user) {
        return Map.of(
                "id", user.id(),
                "version", user.version(),
                "status", user.status(),
                "kind", user.kind(),
                "loginId", user.loginId(),
                "mustChangePassword", user.mustChangePassword(),
                "roleIds", user.roleIds(),
                "permissionOverrides", user.permissionOverrides());
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new OptimisticLockingFailureException("The user version is stale");
        }
    }

    private static void validateSex(String sex) {
        if (sex != null && !Set.of("MALE", "FEMALE", "OTHER").contains(sex)) {
            throw invalid("sex has an unsupported value.");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, detail);
    }
}
