package com.company.erp.identity.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ApiException;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.api.AdminRequests.RoleRequest;
import com.company.erp.identity.api.AdminRequests.RoleUpdateRequest;
import com.company.erp.identity.api.AdminResponses.PermissionPageResponse;
import com.company.erp.identity.api.AdminResponses.RolePageResponse;
import com.company.erp.identity.api.AdminResponses.RoleResponse;
import com.company.erp.identity.api.AuthResponses.PageMetadata;
import com.company.erp.identity.infrastructure.AdminRoleJdbcRepository;
import com.company.erp.identity.infrastructure.AdminUserJdbcRepository;
import com.company.erp.identity.security.ErpPrincipal;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleAdministrationService {

    private final AdminRoleJdbcRepository repository;
    private final AdminUserJdbcRepository userRepository;
    private final UserAdministrationService userService;
    private final AuditEventWriter auditWriter;

    public RoleAdministrationService(
            AdminRoleJdbcRepository repository,
            AdminUserJdbcRepository userRepository,
            UserAdministrationService userService,
            AuditEventWriter auditWriter) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.userService = userService;
        this.auditWriter = auditWriter;
    }

    public RolePageResponse listRoles(int page, int size, List<String> requestedSort, Boolean active) {
        AdminQuery.validatePage(page, size);
        List<String> sort = repository.normalizeRoleSort(requestedSort);
        long total = repository.countRoles(active);
        return new RolePageResponse(
                repository.list(page, size, active, sort),
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters("active", active),
                sort);
    }

    public RoleResponse getRole(UUID roleId) {
        return repository.find(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("app role", roleId.toString()));
    }

    @Transactional
    public RoleResponse createRole(RoleRequest request, ErpPrincipal actor, String requestId) {
        validatePermissionIds(request.permissionIds());
        UUID roleId = repository.create(
                new RoleRequest(
                        request.code().trim().toUpperCase(Locale.ROOT),
                        request.name(),
                        request.description(),
                        request.permissionIds()),
                actor.user().id());
        RoleResponse created = getRole(roleId);
        auditWriter.write(
                actor.user().id(), "ROLE_CREATED", "APP_ROLE", roleId,
                requestId, null, null, summary(created));
        return created;
    }

    @Transactional
    public RoleResponse updateRole(
            UUID roleId,
            long version,
            RoleUpdateRequest request,
            ErpPrincipal actor,
            String requestId) {
        RoleResponse before = getRole(roleId);
        validatePermissionIds(request.permissionIds());
        userRepository.lockRecoveryState();
        if (repository.update(roleId, version, request, actor.user().id()) != 1) {
            throw new OptimisticLockingFailureException("The role version is stale");
        }
        repository.replacePermissions(roleId, request.permissionIds(), actor.user().id());
        userService.enforceRecoveryQuorum();
        RoleResponse after = getRole(roleId);
        auditWriter.write(
                actor.user().id(), "ROLE_UPDATED", "APP_ROLE", roleId,
                requestId, request.reason(), summary(before), summary(after));
        return after;
    }

    public PermissionPageResponse listPermissions(
            int page,
            int size,
            List<String> requestedSort,
            String module) {
        AdminQuery.validatePage(page, size);
        String normalizedModule = module == null || module.isBlank()
                ? null
                : module.trim().toUpperCase(Locale.ROOT);
        List<String> sort = repository.normalizePermissionSort(requestedSort);
        long total = repository.countPermissions(normalizedModule);
        return new PermissionPageResponse(
                repository.listPermissions(page, size, normalizedModule, sort),
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters("module", normalizedModule),
                sort);
    }

    private void validatePermissionIds(List<UUID> permissionIds) {
        if (permissionIds.stream().distinct().count() != permissionIds.size()
                || !repository.activePermissionsExist(permissionIds)) {
            throw new ApiException(
                    ApiErrorCode.VALIDATION_FAILED,
                    "Permission grants must reference unique active seeded permissions.");
        }
    }

    private static Map<String, Object> summary(RoleResponse role) {
        return Map.of(
                "id", role.id(),
                "version", role.version(),
                "code", role.code(),
                "name", role.name(),
                "active", role.active(),
                "permissionIds", role.permissionIds());
    }
}
