package com.company.erp.identity.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.audit.AuditEventWriter;
import com.company.erp.identity.api.AdminResponses.IpAllowlistEntryResponse;
import com.company.erp.identity.api.AdminResponses.IpAllowlistPageResponse;
import com.company.erp.identity.api.AdminResponses.LoginEventPageResponse;
import com.company.erp.identity.api.AuthResponses.PageMetadata;
import com.company.erp.identity.infrastructure.MonitoringJdbcRepository;
import com.company.erp.identity.security.ErpPrincipal;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringAdministrationService {

    private static final Set<String> OUTCOMES = Set.of(
            "SUCCESS", "INVALID_CREDENTIALS", "DISABLED", "BLOCKED_IP", "LOCKED", "ERROR");

    private final MonitoringJdbcRepository repository;
    private final AuditEventWriter auditWriter;

    public MonitoringAdministrationService(
            MonitoringJdbcRepository repository,
            AuditEventWriter auditWriter) {
        this.repository = repository;
        this.auditWriter = auditWriter;
    }

    public LoginEventPageResponse loginEvents(
            int page,
            int size,
            List<String> requestedSort,
            String userId,
            String loginId,
            String outcome,
            String from,
            String to) {
        AdminQuery.validatePage(page, size);
        UUID normalizedUserId = AdminQuery.uuid(userId, "userId");
        String normalizedOutcome = AdminQuery.enumValue(outcome, OUTCOMES, "outcome");
        var fromInstant = AdminQuery.instant(from, "from");
        var toInstant = AdminQuery.instant(to, "to");
        if (fromInstant != null && toInstant != null && !fromInstant.isBefore(toInstant)) {
            throw new com.company.erp.api.ApiException(
                    com.company.erp.api.ApiErrorCode.VALIDATION_FAILED,
                    "from must be earlier than to.");
        }
        List<String> sort = repository.normalizeLoginSort(requestedSort);
        long total = repository.countLoginEvents(
                normalizedUserId, loginId, normalizedOutcome, fromInstant, toInstant);
        return new LoginEventPageResponse(
                repository.loginEvents(
                        page, size, normalizedUserId, loginId, normalizedOutcome, fromInstant, toInstant, sort),
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters(
                        "userId", normalizedUserId,
                        "loginId", loginId == null ? null : loginId.trim(),
                        "outcome", normalizedOutcome,
                        "from", fromInstant,
                        "to", toInstant),
                sort);
    }

    public IpAllowlistPageResponse allowlist(
            int page,
            int size,
            List<String> requestedSort,
            Boolean active) {
        AdminQuery.validatePage(page, size);
        List<String> sort = repository.normalizeAllowlistSort(requestedSort);
        long total = repository.countAllowlist(active);
        return new IpAllowlistPageResponse(
                repository.allowlist(page, size, active, sort),
                new PageMetadata(page, size, total, AdminQuery.totalPages(total, size)),
                AdminQuery.filters("active", active),
                sort,
                false);
    }

    public IpAllowlistEntryResponse getAllowlist(UUID entryId) {
        return repository.findAllowlist(entryId)
                .orElseThrow(() -> new ResourceNotFoundException("IP allowlist entry", entryId.toString()));
    }

    @Transactional
    public IpAllowlistEntryResponse createAllowlist(
            String network,
            String name,
            ErpPrincipal actor,
            String requestId) {
        UUID entryId = repository.createAllowlist(network.trim(), name.trim(), actor.user().id());
        IpAllowlistEntryResponse created = getAllowlist(entryId);
        auditWriter.write(
                actor.user().id(), "IP_ALLOWLIST_CREATED", "IP_ALLOWLIST_ENTRY", entryId,
                requestId, null, null, summary(created));
        return created;
    }

    @Transactional
    public IpAllowlistEntryResponse updateAllowlist(
            UUID entryId,
            long version,
            String network,
            String name,
            boolean active,
            ErpPrincipal actor,
            String requestId) {
        IpAllowlistEntryResponse before = getAllowlist(entryId);
        if (repository.updateAllowlist(
                entryId, version, network.trim(), name.trim(), active, actor.user().id()) != 1) {
            throw new OptimisticLockingFailureException("The allowlist entry version is stale");
        }
        IpAllowlistEntryResponse after = getAllowlist(entryId);
        auditWriter.write(
                actor.user().id(), "IP_ALLOWLIST_UPDATED", "IP_ALLOWLIST_ENTRY", entryId,
                requestId, null, summary(before), summary(after));
        return after;
    }

    @Transactional
    public void deleteAllowlist(
            UUID entryId,
            long version,
            ErpPrincipal actor,
            String requestId) {
        IpAllowlistEntryResponse before = getAllowlist(entryId);
        if (repository.deleteAllowlist(entryId, version) != 1) {
            throw new OptimisticLockingFailureException("The allowlist entry version is stale");
        }
        auditWriter.write(
                actor.user().id(), "IP_ALLOWLIST_DELETED", "IP_ALLOWLIST_ENTRY", entryId,
                requestId, null, summary(before), null);
    }

    private static Map<String, Object> summary(IpAllowlistEntryResponse entry) {
        return Map.of(
                "id", entry.id(),
                "version", entry.version(),
                "network", entry.network(),
                "name", entry.name(),
                "active", entry.active());
    }
}
