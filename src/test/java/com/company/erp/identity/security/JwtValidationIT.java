package com.company.erp.identity.security;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.company.erp.config.ErpSecurityProperties;

import com.company.erp.identity.domain.AppUser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class JwtValidationIT {

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void issuesStrictRs256AccessTokenWithFrozenClaimsAndLifetime() {
        AppUser user = activeUser(false, 7);
        UUID sessionId = UUID.randomUUID();
        Instant before = Instant.now();

        String encoded = jwtTokenService.issueSessionAccessToken(user, sessionId).token();
        var jwt = jwtTokenService.decode(encoded);

        assertThat(jwt.getHeaders())
                .containsEntry("alg", "RS256")
                .containsEntry("kid", "test-ephemeral-rsa-3072");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://erp.example.invalid");
        assertThat(jwt.getAudience()).containsExactly("erp-api");
        assertThat(jwt.getSubject()).isEqualTo(user.id().toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        assertThat(jwt.getClaimAsString("purpose")).isEqualTo("access");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getNotBefore()).isAfterOrEqualTo(before.minusSeconds(1));
    }

    @Test
    void passwordChangeChallengeCarriesGenerationAndCannotMasqueradeAsAccess() {
        AppUser user = activeUser(true, 11);

        var challenge = jwtTokenService.issuePasswordChangeChallenge(user);
        var jwt = jwtTokenService.decode(challenge.token());

        assertThat(jwt.getClaimAsString("purpose")).isEqualTo("password_change");
        assertThat(((Number) jwt.getClaim("password_generation")).longValue()).isEqualTo(11L);
        assertThat(jwtTokenService.isSessionAccess(jwt)).isFalse();
        assertThat(challenge.expiresAt()).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void rejectsMalformedOrIncorrectlySignedTokens() {
        assertThatThrownBy(() -> jwtTokenService.decode("not.a.jwt"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwtTokenService.decode(issueTokenWithUntrustedKey()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTrustedSignatureWithWrongIssuerAudiencePurposeOrTimeWindow() {
        Instant now = Instant.parse("2026-07-29T04:00:00Z");
        RSAKey key = rsaKey("trusted-negative-key");
        ErpSecurityProperties properties = properties(key.getKeyID());
        JwtTokenService service = new JwtTokenService(
                properties,
                key,
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.decode(issueTrustedTokenWithoutKid(
                key, properties.issuer(), properties.audience(), "access", now, now.plusSeconds(60))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.decode(issueTrustedToken(
                key, "https://wrong.example.invalid", "erp-api", "access", now, now.plusSeconds(60))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.decode(issueTrustedToken(
                key, properties.issuer(), "wrong-audience", "access", now, now.plusSeconds(60))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.decode(issueTrustedToken(
                key, properties.issuer(), properties.audience(), "refresh", now, now.plusSeconds(60))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.decode(issueTrustedToken(
                key, properties.issuer(), properties.audience(), "access", now.minusSeconds(120), now.minusSeconds(31))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.decode(issueTrustedToken(
                key, properties.issuer(), properties.audience(), "access", now.plusSeconds(31), now.plusSeconds(120))))
                .isInstanceOf(JwtException.class);
    }

    private static String issueTokenWithUntrustedKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            KeyPair pair = generator.generateKeyPair();
            RSAKey key = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate())
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID("untrusted-key")
                    .build();
            Instant now = Instant.now();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    new JWTClaimsSet.Builder()
                            .issuer("https://erp.example.invalid")
                            .audience("erp-api")
                            .subject(UUID.randomUUID().toString())
                            .jwtID(UUID.randomUUID().toString())
                            .issueTime(Date.from(now))
                            .notBeforeTime(Date.from(now))
                            .expirationTime(Date.from(now.plusSeconds(60)))
                            .claim("purpose", "access")
                            .claim("sid", UUID.randomUUID().toString())
                            .build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create untrusted test token", exception);
        }
    }

    private static String issueTrustedTokenWithoutKid(
            RSAKey key,
            String issuer,
            String audience,
            String purpose,
            Instant notBefore,
            Instant expiresAt) {
        return issueTrustedToken(key, issuer, audience, purpose, notBefore, expiresAt, false);
    }

    private static String issueTrustedToken(
            RSAKey key,
            String issuer,
            String audience,
            String purpose,
            Instant notBefore,
            Instant expiresAt) {
        return issueTrustedToken(key, issuer, audience, purpose, notBefore, expiresAt, true);
    }

    private static String issueTrustedToken(
            RSAKey key,
            String issuer,
            String audience,
            String purpose,
            Instant notBefore,
            Instant expiresAt,
            boolean includeKid) {
        try {
            Instant issuedAt = notBefore.isAfter(expiresAt) ? expiresAt.minusSeconds(1) : notBefore;
            JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256);
            if (includeKid) {
                header.keyID(key.getKeyID());
            }
            SignedJWT jwt = new SignedJWT(
                    header.build(),
                    new JWTClaimsSet.Builder()
                            .issuer(issuer)
                            .audience(audience)
                            .subject(UUID.randomUUID().toString())
                            .jwtID(UUID.randomUUID().toString())
                            .issueTime(Date.from(issuedAt))
                            .notBeforeTime(Date.from(notBefore))
                            .expirationTime(Date.from(expiresAt))
                            .claim("purpose", purpose)
                            .claim("sid", UUID.randomUUID().toString())
                            .build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create trusted test token", exception);
        }
    }

    private static RSAKey rsaKey(String kid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
                    .privateKey((java.security.interfaces.RSAPrivateKey) pair.getPrivate())
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(kid)
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not generate test RSA key", exception);
        }
    }

    private static ErpSecurityProperties properties(String kid) {
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
                List.of(),
                null,
                new ErpSecurityProperties.KeyRing("RS256", 3072, kid, null, List.of()));
    }

    private static AppUser activeUser(boolean mustChangePassword, long generation) {
        return new AppUser(
                UUID.randomUUID(),
                "USER",
                "jwt-user",
                "{argon2}redacted",
                "JWT User",
                "ACTIVE",
                mustChangePassword,
                generation);
    }
}
