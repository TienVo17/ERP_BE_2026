package com.company.erp.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record AuthSession(
        UUID id,
        UUID userId,
        Instant createdAt,
        Instant lastRefreshedAt,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        String clientIp,
        String userAgent,
        boolean current,
        Instant revokedAt) {

    public boolean isActiveAt(Instant instant) {
        return revokedAt == null
                && instant.isBefore(idleExpiresAt)
                && instant.isBefore(absoluteExpiresAt);
    }

    public AuthSession asCurrent(boolean value) {
        return new AuthSession(
                id, userId, createdAt, lastRefreshedAt, idleExpiresAt, absoluteExpiresAt,
                clientIp, userAgent, value, revokedAt);
    }
}
