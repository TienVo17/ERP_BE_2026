package com.company.erp.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.company.erp.config.ErpSecurityProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedIpRateLimiterTest {

    @Test
    void enforcesFrozenLoginAndRefreshLimitsIndependently() {
        ErpSecurityProperties properties = ErpSecurityProperties.defaults();
        TrustedIpRateLimiter limiter = new TrustedIpRateLimiter(
                properties,
                Clock.fixed(Instant.parse("2026-07-29T00:00:00Z"), ZoneOffset.UTC));

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(limiter.tryLogin("192.0.2.10")).isTrue();
        }
        assertThat(limiter.tryLogin("192.0.2.10")).isFalse();
        assertThat(limiter.tryLogin("192.0.2.11")).isTrue();

        for (int attempt = 1; attempt <= 120; attempt++) {
            assertThat(limiter.tryRefresh("192.0.2.10")).isTrue();
        }
        assertThat(limiter.tryRefresh("192.0.2.10")).isFalse();
    }
}
