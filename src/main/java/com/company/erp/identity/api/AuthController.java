package com.company.erp.identity.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.company.erp.api.ApiException;
import com.company.erp.api.ApiErrorCode;
import com.company.erp.config.ErpSecurityProperties;
import com.company.erp.identity.api.AuthRequests.ChangePasswordRequest;
import com.company.erp.identity.api.AuthRequests.LoginRequest;
import com.company.erp.identity.api.AuthResponses.CsrfResponse;
import com.company.erp.identity.api.AuthResponses.CurrentUserResponse;
import com.company.erp.identity.api.AuthResponses.SessionPageResponse;
import com.company.erp.identity.application.AuthenticationService;
import com.company.erp.identity.application.AuthenticationService.ChangePasswordFailure;
import com.company.erp.identity.application.AuthenticationService.ChangePasswordSucceeded;
import com.company.erp.identity.application.AuthenticationService.LoginFailure;
import com.company.erp.identity.application.AuthenticationService.LoginSucceeded;
import com.company.erp.identity.application.AuthenticationService.RefreshFailure;
import com.company.erp.identity.application.AuthenticationService.RefreshSucceeded;
import com.company.erp.identity.security.ErpPrincipal;
import com.company.erp.identity.security.TrustedClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AuthenticationService authenticationService;
    private final ErpSecurityProperties properties;
    private final TrustedClientIpResolver clientIpResolver;

    public AuthController(
            AuthenticationService authenticationService,
            ErpSecurityProperties properties,
            TrustedClientIpResolver clientIpResolver) {
        this.authenticationService = authenticationService;
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/csrf")
    ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        ResponseCookie csrfCookie = ResponseCookie.from("XSRF-TOKEN", token.getToken())
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, csrfCookie.toString())
                .body(new CsrfResponse(token.getToken()));
    }

    @PostMapping("/login")
    ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        String clientIp = clientIpResolver.resolve(servletRequest);
        var result = authenticationService.login(
                request.loginId(),
                request.password(),
                clientIp,
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        if (result instanceof LoginFailure failure) {
            throw new ApiException(failure.errorCode(), failure.detail());
        }
        LoginSucceeded success = (LoginSucceeded) result;
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, clearCsrfCookie().toString());
        if (success.rawRefreshToken() != null) {
            response.header(HttpHeaders.SET_COOKIE, refreshCookie(success.rawRefreshToken()).toString());
        }
        return response.body(success.response());
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {
        var result = authenticationService.refresh(refreshCookieValue(request), clientIpResolver.resolve(request));
        if (result instanceof RefreshFailure failure) {
            if (failure.errorCode() == ApiErrorCode.REFRESH_REAUTH_REQUIRED) {
                response.addHeader(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString());
            }
            throw new ApiException(failure.errorCode(), failure.detail());
        }
        RefreshSucceeded success = (RefreshSucceeded) result;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(success.rawRefreshToken()).toString())
                .body(success.response());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(
            HttpServletRequest request,
            JwtAuthenticationToken authentication) {
        String rawRefreshToken = refreshCookieValue(request);
        if (rawRefreshToken != null && authentication != null) {
            requireDoubleSubmitCsrf(request);
        }
        if (rawRefreshToken != null) {
            authenticationService.logoutRefreshSession(rawRefreshToken);
        }
        if (authentication != null) {
            ErpPrincipal principal = principal(authentication);
            if ("password_change".equals(principal.purpose())) {
                authenticationService.logoutChallenge(principal);
            } else if (rawRefreshToken == null) {
                throw new ApiException(ApiErrorCode.UNAUTHENTICATED, "A refresh session is required to log out.");
            }
        } else if (rawRefreshToken == null) {
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED, "Valid authentication is required.");
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .header(HttpHeaders.SET_COOKIE, clearCsrfCookie().toString())
                .build();
    }

    @GetMapping("/me")
    CurrentUserResponse me(JwtAuthenticationToken authentication) {
        return authenticationService.currentUser(principal(authentication));
    }

    @GetMapping("/sessions")
    SessionPageResponse sessions(
            JwtAuthenticationToken authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @RequestParam(required = false) List<String> sort) {
        return authenticationService.sessions(principal(authentication), page, size, sort);
    }

    @PostMapping("/sessions/{sessionId}/revoke")
    ResponseEntity<Void> revokeSession(
            JwtAuthenticationToken authentication,
            @PathVariable UUID sessionId) {
        boolean revokedCurrent = authenticationService.revokeSession(principal(authentication), sessionId);
        if (revokedCurrent) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                    .build();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    ResponseEntity<?> changePassword(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest servletRequest) {
        var result = authenticationService.changePassword(
                principal(authentication),
                request,
                clientIpResolver.resolve(servletRequest),
                servletRequest.getHeader(HttpHeaders.USER_AGENT));
        if (result instanceof ChangePasswordFailure failure) {
            throw new ApiException(failure.errorCode(), failure.detail());
        }
        ChangePasswordSucceeded success = (ChangePasswordSucceeded) result;
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookie(success.rawRefreshToken()).toString())
                .body(success.response());
    }

    private ResponseCookie refreshCookie(String value) {
        var cookie = properties.refreshCookie();
        return ResponseCookie.from(cookie.name(), value)
                .httpOnly(cookie.httpOnly())
                .secure(cookie.secure())
                .sameSite(cookie.sameSite())
                .path(cookie.path())
                .maxAge(properties.refreshAbsoluteTtl())
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        var cookie = properties.refreshCookie();
        return ResponseCookie.from(cookie.name(), "")
                .httpOnly(cookie.httpOnly())
                .secure(cookie.secure())
                .sameSite(cookie.sameSite())
                .path(cookie.path())
                .maxAge(Duration.ZERO)
                .build();
    }

    private static ResponseCookie clearCsrfCookie() {
        return ResponseCookie.from("XSRF-TOKEN", "")
                .httpOnly(false)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private void requireDoubleSubmitCsrf(HttpServletRequest request) {
        String cookieToken = cookieValue(request, "XSRF-TOKEN");
        String headerToken = request.getHeader("X-CSRF-Token");
        if (cookieToken == null
                || headerToken == null
                || !java.security.MessageDigest.isEqual(
                        cookieToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        headerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ApiException(ApiErrorCode.CSRF_INVALID, "A valid CSRF token is required.");
        }
    }

    private String refreshCookieValue(HttpServletRequest request) {
        return cookieValue(request, properties.refreshCookie().name());
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static ErpPrincipal principal(JwtAuthenticationToken authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof ErpPrincipal principal)) {
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED, "Valid authentication is required.");
        }
        return principal;
    }

}
