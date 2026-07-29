package com.company.erp.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.company.erp.api.ApiErrorCode;
import com.company.erp.api.ResourceNotFoundException;
import com.company.erp.identity.api.AuthRequests.ChangePasswordRequest;
import com.company.erp.identity.api.AuthResponses;
import com.company.erp.identity.api.AuthResponses.CurrentUserResponse;
import com.company.erp.identity.api.AuthResponses.NormalAuthResponse;
import com.company.erp.identity.api.AuthResponses.PageMetadata;
import com.company.erp.identity.api.AuthResponses.PasswordChangeChallengeResponse;
import com.company.erp.identity.api.AuthResponses.SessionPageResponse;
import com.company.erp.identity.application.RefreshTokenService.ReauthenticationRequired;
import com.company.erp.identity.application.RefreshTokenService.RotationSucceeded;
import com.company.erp.identity.domain.AppUser;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.identity.security.JwtTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthenticationService {

    private static final String GENERIC_LOGIN_DETAIL = "The supplied credentials are invalid.";

    private final IdentityJdbcRepository repository;
    private final PasswordService passwordService;
    private final RefreshTokenService refreshTokenService;
    private final JwtTokenService jwtTokenService;
    private final TrustedIpRateLimiter rateLimiter;
    private final Clock clock;
    private final String dummyPasswordHash;

    @Autowired
    public AuthenticationService(
            IdentityJdbcRepository repository,
            PasswordService passwordService,
            RefreshTokenService refreshTokenService,
            JwtTokenService jwtTokenService,
            TrustedIpRateLimiter rateLimiter) {
        this(repository, passwordService, refreshTokenService, jwtTokenService, rateLimiter, Clock.systemUTC());
    }

    AuthenticationService(
            IdentityJdbcRepository repository,
            PasswordService passwordService,
            RefreshTokenService refreshTokenService,
            JwtTokenService jwtTokenService,
            TrustedIpRateLimiter rateLimiter,
            Clock clock) {
        this.repository = repository;
        this.passwordService = passwordService;
        this.refreshTokenService = refreshTokenService;
        this.jwtTokenService = jwtTokenService;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.dummyPasswordHash = passwordService.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public LoginResult login(
            String loginId,
            String password,
            String clientIp,
            String userAgent) {
        if (!rateLimiter.tryLogin(clientIp)) {
            repository.recordLoginEvent(null, loginId, "BLOCKED_IP", clientIp, userAgent);
            return new LoginFailure(ApiErrorCode.RATE_LIMITED, "Too many authentication attempts.");
        }

        var candidate = repository.findByCanonicalLoginId(loginId);
        AppUser user = candidate.orElse(null);
        boolean passwordMatches = passwordService.matches(
                password,
                user == null ? dummyPasswordHash : user.passwordHash());
        if (user == null || !passwordMatches) {
            repository.recordLoginEvent(
                    user == null ? null : user.id(), loginId, "INVALID_CREDENTIALS", clientIp, userAgent);
            return new LoginFailure(ApiErrorCode.UNAUTHENTICATED, GENERIC_LOGIN_DETAIL);
        }
        if (!user.isActive()) {
            repository.recordLoginEvent(user.id(), loginId, "DISABLED", clientIp, userAgent);
            return new LoginFailure(ApiErrorCode.UNAUTHENTICATED, GENERIC_LOGIN_DETAIL);
        }
        if (user.mustChangePassword()) {
            var challenge = jwtTokenService.issuePasswordChangeChallenge(user);
            repository.recordLoginEvent(user.id(), loginId, "SUCCESS", clientIp, userAgent);
            return new LoginSucceeded(
                    PasswordChangeChallengeResponse.of(challenge.token(), challenge.expiresAt(), user),
                    null);
        }

        var refresh = refreshTokenService.createSession(user, clientIp, userAgent);
        var access = jwtTokenService.issueSessionAccessToken(user, refresh.session().id());
        repository.recordLoginEvent(user.id(), loginId, "SUCCESS", clientIp, userAgent);
        return new LoginSucceeded(
                NormalAuthResponse.of(access.token(), jwtTokenService.accessTtlSeconds(), refresh.session()),
                refresh.rawRefreshToken());
    }

    @Transactional
    public RefreshResult refresh(String rawRefreshToken, String clientIp) {
        if (!rateLimiter.tryRefresh(clientIp)) {
            return new RefreshFailure(ApiErrorCode.RATE_LIMITED, "Too many refresh attempts.");
        }
        var result = refreshTokenService.rotate(rawRefreshToken);
        if (result instanceof ReauthenticationRequired) {
            return new RefreshFailure(
                    ApiErrorCode.REFRESH_REAUTH_REQUIRED,
                    "Interactive login is required.");
        }
        RotationSucceeded rotation = (RotationSucceeded) result;
        AppUser user = repository.findById(rotation.userId())
                .filter(AppUser::isActive)
                .orElse(null);
        if (user == null || user.mustChangePassword()) {
            refreshTokenService.revokeByRawToken(rotation.rawRefreshToken(), "USER_INACTIVE_OR_PASSWORD_CHANGE");
            return new RefreshFailure(
                    ApiErrorCode.REFRESH_REAUTH_REQUIRED,
                    "Interactive login is required.");
        }
        var access = jwtTokenService.issueSessionAccessToken(user, rotation.session().id());
        return new RefreshSucceeded(
                NormalAuthResponse.of(access.token(), jwtTokenService.accessTtlSeconds(), rotation.session()),
                rotation.rawRefreshToken());
    }

    public boolean logoutRefreshSession(String rawRefreshToken) {
        return refreshTokenService.revokeByRawToken(rawRefreshToken, "LOGOUT");
    }

    @Transactional
    public void logoutChallenge(ErpPrincipal principal) {
        if (!"password_change".equals(principal.purpose())) {
            return;
        }
        repository.invalidatePasswordChallenge(
                principal.user().id(),
                principal.user().passwordGeneration());
    }

    public CurrentUserResponse currentUser(ErpPrincipal principal) {
        AppUser user = principal.user();
        return new CurrentUserResponse(
                user.id(),
                user.loginId(),
                user.name(),
                user.status(),
                repository.roleCodes(user.id()).stream().sorted().toList(),
                repository.effectivePermissions(user.id()),
                user.mustChangePassword());
    }

    public SessionPageResponse sessions(
            ErpPrincipal principal,
            int page,
            int size,
            List<String> requestedSort) {
        validatePage(page, size);
        List<String> sort = SessionSort.normalize(requestedSort);
        Instant now = clock.instant();
        long total = repository.countSessions(principal.user().id(), now);
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        List<AuthResponses.SessionResponse> items = repository
                .listSessions(principal.user().id(), page, size, principal.sessionId(), now, sort)
                .stream()
                .map(AuthResponses.SessionResponse::from)
                .toList();
        return new SessionPageResponse(
                items,
                new PageMetadata(page, size, total, totalPages),
                java.util.Map.of(),
                sort);
    }

    @Transactional
    public boolean revokeSession(ErpPrincipal principal, UUID sessionId) {
        Instant now = clock.instant();
        if (repository.findOwnedSession(sessionId, principal.user().id(), principal.sessionId(), now).isEmpty()) {
            throw new ResourceNotFoundException("auth session", sessionId.toString());
        }
        repository.revokeOwnedSession(sessionId, principal.user().id(), now, "USER_REVOKED");
        return sessionId.equals(principal.sessionId());
    }

    @Transactional
    public ChangePasswordResult changePassword(
            ErpPrincipal principal,
            ChangePasswordRequest request,
            String clientIp,
            String userAgent) {
        AppUser current = repository.findById(principal.user().id())
                .filter(AppUser::isActive)
                .orElse(null);
        if (current == null
                || !passwordService.matches(request.currentPassword(), current.passwordHash())
                || current.passwordGeneration() != principal.user().passwordGeneration()) {
            return new ChangePasswordFailure(ApiErrorCode.UNAUTHENTICATED, "The current password is invalid.");
        }
        if (passwordService.matches(request.newPassword(), current.passwordHash())) {
            return new ChangePasswordFailure(ApiErrorCode.VALIDATION_FAILED, "The new password must be different.");
        }

        Instant now = clock.instant();
        int updated = repository.changePassword(
                current.id(),
                current.passwordGeneration(),
                passwordService.encode(request.newPassword()),
                now);
        if (updated != 1) {
            return new ChangePasswordFailure(ApiErrorCode.UNAUTHENTICATED, "The password challenge is no longer valid.");
        }
        repository.revokeAllSessions(current.id(), now, "PASSWORD_CHANGED");
        AppUser updatedUser = repository.findById(current.id()).orElseThrow();
        var refresh = refreshTokenService.createSession(updatedUser, clientIp, userAgent);
        var access = jwtTokenService.issueSessionAccessToken(updatedUser, refresh.session().id());
        return new ChangePasswordSucceeded(
                NormalAuthResponse.of(access.token(), jwtTokenService.accessTtlSeconds(), refresh.session()),
                refresh.rawRefreshToken());
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
    }

    public sealed interface LoginResult permits LoginSucceeded, LoginFailure {
    }

    public record LoginSucceeded(Object response, String rawRefreshToken) implements LoginResult {
    }

    public record LoginFailure(ApiErrorCode errorCode, String detail) implements LoginResult {
    }

    public sealed interface RefreshResult permits RefreshSucceeded, RefreshFailure {
    }

    public record RefreshSucceeded(NormalAuthResponse response, String rawRefreshToken) implements RefreshResult {
    }

    public record RefreshFailure(ApiErrorCode errorCode, String detail) implements RefreshResult {
    }

    public sealed interface ChangePasswordResult permits ChangePasswordSucceeded, ChangePasswordFailure {
    }

    public record ChangePasswordSucceeded(NormalAuthResponse response, String rawRefreshToken)
            implements ChangePasswordResult {
    }

    public record ChangePasswordFailure(ApiErrorCode errorCode, String detail)
            implements ChangePasswordResult {
    }
}
