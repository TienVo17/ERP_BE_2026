package com.company.erp.identity.security;

import java.time.Duration;
import java.util.List;

import com.company.erp.config.ErpSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedClientIpResolverTest {

    @Test
    void takesTheRightmostUntrustedHopSoClientSuppliedPrefixesCannotForgeTheKey() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(properties(List.of("10.0.0.5")));

        // nginx appends the peer to whatever the client sent, so "203.0.113.9" here is attacker text.
        MockHttpServletRequest spoofedPrefix = new MockHttpServletRequest();
        spoofedPrefix.setRemoteAddr("10.0.0.5");
        spoofedPrefix.addHeader("X-Forwarded-For", "203.0.113.9, 198.51.100.7");
        assertThat(resolver.resolve(spoofedPrefix)).isEqualTo("198.51.100.7");

        MockHttpServletRequest singleProxyHop = new MockHttpServletRequest();
        singleProxyHop.setRemoteAddr("10.0.0.5");
        singleProxyHop.addHeader("X-Forwarded-For", "198.51.100.7");
        assertThat(resolver.resolve(singleProxyHop)).isEqualTo("198.51.100.7");
    }

    @Test
    void skipsTrustedProxyHopsAndFallsBackToThePeerWhenEveryHopIsTrusted() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(
                properties(List.of("10.0.0.5", "10.0.0.6")));

        MockHttpServletRequest chainedProxies = new MockHttpServletRequest();
        chainedProxies.setRemoteAddr("10.0.0.5");
        chainedProxies.addHeader("X-Forwarded-For", "198.51.100.7, 10.0.0.6");
        assertThat(resolver.resolve(chainedProxies)).isEqualTo("198.51.100.7");

        MockHttpServletRequest onlyTrustedHops = new MockHttpServletRequest();
        onlyTrustedHops.setRemoteAddr("10.0.0.5");
        onlyTrustedHops.addHeader("X-Forwarded-For", "10.0.0.6");
        assertThat(resolver.resolve(onlyTrustedHops)).isEqualTo("10.0.0.5");
    }

    @Test
    void ignoresForwardedHeadersWhenTheImmediatePeerIsNotATrustedProxy() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(properties(List.of("10.0.0.5")));

        MockHttpServletRequest directCall = new MockHttpServletRequest();
        directCall.setRemoteAddr("198.51.100.7");
        directCall.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(resolver.resolve(directCall)).isEqualTo("198.51.100.7");

        TrustedClientIpResolver noProxies = new TrustedClientIpResolver(properties(List.of()));
        MockHttpServletRequest untrustedTopology = new MockHttpServletRequest();
        untrustedTopology.setRemoteAddr("10.0.0.5");
        untrustedTopology.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(noProxies.resolve(untrustedTopology)).isEqualTo("10.0.0.5");
    }

    @Test
    void rejectsHostnamesAndMalformedHopsWithoutResolvingThemOrFailingTheRequest() {
        TrustedClientIpResolver resolver = new TrustedClientIpResolver(properties(List.of("10.0.0.5")));

        MockHttpServletRequest hostnameHop = new MockHttpServletRequest();
        hostnameHop.setRemoteAddr("10.0.0.5");
        hostnameHop.addHeader("X-Forwarded-For", "a.attacker.example.com");
        assertThat(resolver.resolve(hostnameHop)).isEqualTo("10.0.0.5");

        MockHttpServletRequest mixedHops = new MockHttpServletRequest();
        mixedHops.setRemoteAddr("10.0.0.5");
        mixedHops.addHeader("X-Forwarded-For", "198.51.100.7, unknown, 999.1.1.1");
        assertThat(resolver.resolve(mixedHops)).isEqualTo("198.51.100.7");
    }

    private static ErpSecurityProperties properties(List<String> trustedProxies) {
        return new ErpSecurityProperties(
                "https://erp.example.invalid",
                "erp-api",
                Duration.ofMinutes(15),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                Duration.ofHours(8),
                Duration.ofHours(1),
                10,
                120,
                1,
                trustedProxies,
                null,
                null);
    }
}
