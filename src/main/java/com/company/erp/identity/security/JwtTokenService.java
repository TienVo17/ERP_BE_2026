package com.company.erp.identity.security;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.company.erp.config.ErpSecurityProperties;
import com.company.erp.identity.domain.AppUser;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Service;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JwtTokenService {

    private static final String ACCESS_PURPOSE = "access";
    private static final String PASSWORD_CHANGE_PURPOSE = "password_change";

    private final ErpSecurityProperties properties;
    private final RSAKey signingKey;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Clock clock;

    @Autowired
    public JwtTokenService(ErpSecurityProperties properties, RSAKey signingKey) {
        this(properties, signingKey, Clock.systemUTC());
    }

    JwtTokenService(ErpSecurityProperties properties, RSAKey signingKey, Clock clock) {
        if (!JWSAlgorithm.RS256.equals(signingKey.getAlgorithm())
                || signingKey.size() < properties.keyRing().rsaBits()
                || signingKey.getKeyID() == null
                || !signingKey.getKeyID().equals(properties.keyRing().activeKid())
                || !signingKey.isPrivate()) {
            throw new IllegalStateException("Active JWT key must be the configured RSA-3072 RS256 signing key");
        }
        this.properties = properties;
        this.signingKey = signingKey;
        this.clock = clock;
        JWKSource<SecurityContext> signingSource = new ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(signingKey));
        this.encoder = new NimbusJwtEncoder(signingSource);
        JWKSource<SecurityContext> verificationSource = new ImmutableJWKSet<>(verificationKeySet(properties, signingKey));
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSource(verificationSource)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        JwtTimestampValidator timestamp = new JwtTimestampValidator(properties.acceptedClockSkew());
        timestamp.setClock(clock);
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                timestamp,
                jwt -> jwt.getHeaders().get("kid") instanceof String kid && !kid.isBlank()
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token", "JWT kid header is required", null)),
                new JwtClaimValidator<List<String>>("aud", audience ->
                        audience != null && audience.contains(properties.audience())),
                new JwtClaimValidator<String>("sub", JwtTokenService::isUuid),
                new JwtClaimValidator<String>("jti", JwtTokenService::isUuid),
                new JwtClaimValidator<String>("purpose", purpose -> Set.of(
                        ACCESS_PURPOSE,
                        PASSWORD_CHANGE_PURPOSE).contains(purpose))));
        this.decoder = jwtDecoder;
    }

    public IssuedToken issueSessionAccessToken(AppUser user, UUID sessionId) {
        return issue(user, sessionId, ACCESS_PURPOSE, user.passwordGeneration(), properties.accessTtl().toSeconds());
    }

    public IssuedToken issuePasswordChangeChallenge(AppUser user) {
        return issue(
                user,
                null,
                PASSWORD_CHANGE_PURPOSE,
                user.passwordGeneration(),
                properties.passwordChangeTtl().toSeconds());
    }

    public Jwt decode(String encoded) {
        return decoder.decode(encoded);
    }

    public boolean isSessionAccess(Jwt jwt) {
        return ACCESS_PURPOSE.equals(jwt.getClaimAsString("purpose"))
                && isUuid(jwt.getClaimAsString("sid"));
    }

    public boolean isPasswordChangeChallenge(Jwt jwt) {
        return PASSWORD_CHANGE_PURPOSE.equals(jwt.getClaimAsString("purpose"))
                && numericClaim(jwt, "password_generation") != null;
    }

    public long accessTtlSeconds() {
        return properties.accessTtl().toSeconds();
    }

    private IssuedToken issue(
            AppUser user,
            UUID sessionId,
            String purpose,
            long passwordGeneration,
            long lifetimeSeconds) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plusSeconds(lifetimeSeconds);
        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(signingKey.getKeyID())
                .build();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(user.id().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .claim("purpose", purpose)
                .claim("password_generation", passwordGeneration);
        if (sessionId != null) {
            claims.claim("sid", sessionId.toString());
        }
        Jwt jwt = encoder.encode(JwtEncoderParameters.from(headers, claims.build()));
        return new IssuedToken(jwt.getTokenValue(), expiresAt);
    }

    private static com.nimbusds.jose.jwk.JWKSet verificationKeySet(
            ErpSecurityProperties properties,
            RSAKey signingKey) {
        List<com.nimbusds.jose.jwk.JWK> keys = new ArrayList<>();
        keys.add(signingKey.toPublicJWK());
        Set<String> kids = new HashSet<>();
        kids.add(signingKey.getKeyID());
        for (String location : properties.keyRing().publicKeyLocations()) {
            com.nimbusds.jose.jwk.JWK parsed = JwtKeyFiles.load(location);
            if (!(parsed instanceof RSAKey rsaKey)
                    || rsaKey.size() < properties.keyRing().rsaBits()
                    || rsaKey.getKeyID() == null
                    || !JWSAlgorithm.RS256.equals(rsaKey.getAlgorithm())) {
                throw new IllegalStateException(
                        "Verification keys must be RSA-3072 RS256 public JWKs with kid");
            }
            if (!kids.add(rsaKey.getKeyID())) {
                throw new IllegalStateException("JWT verification key IDs must be unique");
            }
            keys.add(rsaKey.toPublicJWK());
        }
        return new com.nimbusds.jose.jwk.JWKSet(keys);
    }

    static Long numericClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
