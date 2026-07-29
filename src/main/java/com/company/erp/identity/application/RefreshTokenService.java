package com.company.erp.identity.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import com.company.erp.config.ErpSecurityProperties;
import com.company.erp.identity.domain.AppUser;
import com.company.erp.identity.domain.AuthSession;
import com.company.erp.identity.infrastructure.AuthSessionJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthSessionJdbcRepository repository;
    private final ErpSecurityProperties properties;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(
            AuthSessionJdbcRepository repository,
            ErpSecurityProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    RefreshTokenService(
            AuthSessionJdbcRepository repository,
            ErpSecurityProperties properties,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken createSession(AppUser user, String clientIp, String userAgent) {
        Instant now = now();
        Instant absoluteExpiresAt = now.plus(properties.refreshAbsoluteTtl());
        Instant idleExpiresAt = now.plus(properties.refreshIdleTtl());
        UUID sessionId = UUID.randomUUID();
        AuthSession session = new AuthSession(
                sessionId,
                user.id(),
                now,
                now,
                idleExpiresAt,
                absoluteExpiresAt,
                clientIp,
                truncate(userAgent, 500),
                true,
                null);
        UUID tokenId = UUID.randomUUID();
        String rawToken = randomToken();
        repository.insertSession(session);
        repository.insertRefreshToken(
                tokenId,
                sessionId,
                hash(rawToken),
                null,
                absoluteExpiresAt,
                now);
        return new IssuedRefreshToken(session, tokenId, rawToken);
    }

    @Transactional
    public RotationResult rotate(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return new ReauthenticationRequired();
        }
        Instant now = now();
        var locked = repository.lockByHash(hash(rawRefreshToken));
        if (locked.isEmpty()) {
            return new ReauthenticationRequired();
        }
        var current = locked.orElseThrow();
        if (!"ACTIVE".equals(current.status())
                || current.revokedAt() != null
                || !now.isBefore(current.tokenExpiresAt())
                || !now.isBefore(current.idleExpiresAt())
                || !now.isBefore(current.absoluteExpiresAt())) {
            repository.revokeFamily(current.sessionId(), now, "REFRESH_REPLAY_OR_EXPIRED");
            return new ReauthenticationRequired();
        }

        repository.markUsed(current.tokenId(), now);
        Instant idleExpiresAt = min(
                now.plus(properties.refreshIdleTtl()),
                current.absoluteExpiresAt());
        repository.advanceIdleDeadline(current.sessionId(), now, idleExpiresAt);
        UUID nextTokenId = UUID.randomUUID();
        String nextRawToken = randomToken();
        repository.insertRefreshToken(
                nextTokenId,
                current.sessionId(),
                hash(nextRawToken),
                current.tokenId(),
                current.absoluteExpiresAt(),
                now);
        AuthSession session = new AuthSession(
                current.sessionId(),
                current.userId(),
                current.createdAt(),
                now,
                idleExpiresAt,
                current.absoluteExpiresAt(),
                current.clientIp(),
                current.userAgent(),
                true,
                null);
        return new RotationSucceeded(session, current.userId(), nextTokenId, nextRawToken);
    }

    @Transactional
    public boolean revokeByRawToken(String rawRefreshToken, String reason) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return false;
        }
        var locked = repository.lockByHash(hash(rawRefreshToken));
        if (locked.isEmpty()) {
            return false;
        }
        repository.revokeFamily(locked.orElseThrow().sessionId(), now(), reason);
        return true;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String randomToken() {
        byte[] value = new byte[32];
        SECURE_RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] hash(String rawToken) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public sealed interface RotationResult permits RotationSucceeded, ReauthenticationRequired {
    }

    public record RotationSucceeded(
            AuthSession session,
            UUID userId,
            UUID refreshTokenId,
            String rawRefreshToken) implements RotationResult {
    }

    public record ReauthenticationRequired() implements RotationResult {
    }

    public record IssuedRefreshToken(
            AuthSession session,
            UUID refreshTokenId,
            String rawRefreshToken) {
    }
}
