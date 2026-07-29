package com.company.erp.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("erp.security")
@Validated
public record ErpSecurityProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTtl,
        @NotNull Duration refreshAbsoluteTtl,
        @NotNull Duration refreshIdleTtl,
        @Positive Integer loginRateLimitPerMinute,
        @Positive Integer refreshRateLimitPerMinute,
        @NotNull @Valid RefreshCookie refreshCookie,
        @NotNull @Valid KeyRing keyRing) {

    public ErpSecurityProperties {
        issuer = defaultIfBlank(issuer, "https://erp.example.invalid");
        audience = defaultIfBlank(audience, "erp-api");
        accessTtl = accessTtl == null ? Duration.ofMinutes(15) : accessTtl;
        refreshAbsoluteTtl = refreshAbsoluteTtl == null ? Duration.ofHours(8) : refreshAbsoluteTtl;
        refreshIdleTtl = refreshIdleTtl == null ? Duration.ofMinutes(60) : refreshIdleTtl;
        loginRateLimitPerMinute = loginRateLimitPerMinute == null ? 10 : loginRateLimitPerMinute;
        refreshRateLimitPerMinute = refreshRateLimitPerMinute == null ? 120 : refreshRateLimitPerMinute;
        refreshCookie = refreshCookie == null ? new RefreshCookie(null, null, true, true, null) : refreshCookie;
        keyRing = keyRing == null ? new KeyRing(null, 0, null, null, null) : keyRing;
    }

    @AssertTrue(message = "access TTL must be positive")
    public boolean isAccessTtlPositive() {
        return accessTtl != null && !accessTtl.isZero() && !accessTtl.isNegative();
    }

    @AssertTrue(message = "refresh TTLs must be positive")
    public boolean areRefreshTtlsPositive() {
        return refreshAbsoluteTtl != null && !refreshAbsoluteTtl.isZero() && !refreshAbsoluteTtl.isNegative()
                && refreshIdleTtl != null && !refreshIdleTtl.isZero() && !refreshIdleTtl.isNegative();
    }

    @AssertTrue(message = "refresh idle TTL must not exceed absolute TTL")
    public boolean isRefreshIdleWithinAbsoluteLifetime() {
        return refreshIdleTtl == null || refreshAbsoluteTtl == null
                || refreshIdleTtl.compareTo(refreshAbsoluteTtl) <= 0;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public record RefreshCookie(
            @NotBlank String name,
            @NotBlank String path,
            boolean secure,
            boolean httpOnly,
            @NotBlank String sameSite) {

        public RefreshCookie {
            name = defaultIfBlank(name, "ERP_REFRESH");
            path = defaultIfBlank(path, "/api/v1/auth");
            sameSite = defaultIfBlank(sameSite, "Strict");
        }

        @AssertTrue(message = "refresh cookie must be Secure and HttpOnly")
        public boolean isProtectedFromScriptAccess() {
            return secure && httpOnly;
        }

        @AssertTrue(message = "refresh cookie SameSite policy must be Strict")
        public boolean isStrictSameSite() {
            return "Strict".equalsIgnoreCase(sameSite);
        }
    }

    public record KeyRing(
            @NotBlank String algorithm,
            @Min(3072) int rsaBits,
            String activeKid,
            String privateKeyLocation,
            List<String> publicKeyLocations) {

        public KeyRing {
            algorithm = defaultIfBlank(algorithm, "RS256");
            rsaBits = rsaBits == 0 ? 3072 : rsaBits;
            activeKid = blankToNull(activeKid);
            privateKeyLocation = blankToNull(privateKeyLocation);
            publicKeyLocations = publicKeyLocations == null ? List.of() : List.copyOf(publicKeyLocations);
        }

        @AssertTrue(message = "only RS256 is supported")
        public boolean isRs256() {
            return "RS256".equals(algorithm);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
