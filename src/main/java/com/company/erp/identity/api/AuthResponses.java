package com.company.erp.identity.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.company.erp.identity.domain.AppUser;
import com.company.erp.identity.domain.AuthSession;

public final class AuthResponses {

    private AuthResponses() {
    }

    public record CsrfResponse(String token) {
    }

    public record SessionResponse(
            UUID id,
            String clientIp,
            String userAgent,
            Instant createdAt,
            Instant lastRefreshedAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt,
            boolean current) {

        public static SessionResponse from(AuthSession session) {
            return new SessionResponse(
                    session.id(),
                    session.clientIp(),
                    session.userAgent(),
                    session.createdAt(),
                    session.lastRefreshedAt(),
                    session.idleExpiresAt(),
                    session.absoluteExpiresAt(),
                    session.current());
        }
    }

    public record NormalAuthResponse(
            String purpose,
            String accessToken,
            String tokenType,
            long expiresIn,
            SessionResponse session) {

        public static NormalAuthResponse of(String token, long expiresIn, AuthSession session) {
            return new NormalAuthResponse(
                    "session", token, "Bearer", expiresIn, SessionResponse.from(session.asCurrent(true)));
        }
    }

    public record ChallengeUserResponse(UUID id, String loginId, String name) {
        public static ChallengeUserResponse from(AppUser user) {
            return new ChallengeUserResponse(user.id(), user.loginId(), user.name());
        }
    }

    public record PasswordChangeChallengeResponse(
            String purpose,
            String accessToken,
            String tokenType,
            Instant expiresAt,
            ChallengeUserResponse user) {

        public static PasswordChangeChallengeResponse of(String token, Instant expiresAt, AppUser user) {
            return new PasswordChangeChallengeResponse(
                    "password_change", token, "Bearer", expiresAt, ChallengeUserResponse.from(user));
        }
    }

    public record PermissionResponse(
            UUID id,
            String module,
            String action,
            String description,
            boolean active) {
    }

    public record CurrentUserResponse(
            UUID id,
            String loginId,
            String name,
            String status,
            List<String> roles,
            List<PermissionResponse> permissions,
            boolean mustChangePassword) {
    }

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public record SessionPageResponse(
            List<SessionResponse> items,
            PageMetadata page,
            Map<String, Object> filters,
            List<String> sort) {
    }
}
