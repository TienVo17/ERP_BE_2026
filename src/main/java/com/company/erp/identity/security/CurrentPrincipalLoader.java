package com.company.erp.identity.security;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class CurrentPrincipalLoader implements Converter<Jwt, JwtAuthenticationToken> {

    private final IdentityJdbcRepository repository;
    private final Clock clock;

    @Autowired
    public CurrentPrincipalLoader(IdentityJdbcRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CurrentPrincipalLoader(IdentityJdbcRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public JwtAuthenticationToken convert(Jwt jwt) {
        UUID userId = uuid(jwt.getSubject());
        String purpose = jwt.getClaimAsString("purpose");
        List<SimpleGrantedAuthority> authorities;
        UUID sessionId = null;
        com.company.erp.identity.domain.AppUser user;
        if ("password_change".equals(purpose)) {
            user = repository.findById(userId)
                    .filter(candidate -> candidate.isActive())
                    .orElseThrow(() -> new BadCredentialsException("Invalid bearer principal"));
            Long generation = JwtTokenService.numericClaim(jwt, "password_generation");
            if (!user.mustChangePassword()
                    || generation == null
                    || generation != user.passwordGeneration()) {
                throw new BadCredentialsException("Invalid password change challenge");
            }
            authorities = List.of(new SimpleGrantedAuthority("PASSWORD_CHANGE_CHALLENGE"));
        } else if ("access".equals(purpose)) {
            sessionId = uuid(jwt.getClaimAsString("sid"));
            var authorization = repository.findAccessAuthorization(userId, sessionId, clock.instant())
                    .orElseThrow(() -> new BadCredentialsException("Invalid bearer principal or session"));
            user = authorization.user();
            authorities = authorization.authorities().stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
        } else {
            throw new BadCredentialsException("Unsupported token purpose");
        }
        ErpPrincipal principal = new ErpPrincipal(user, sessionId, purpose);
        return new JwtAuthenticationToken(jwt, principal, authorities);
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new BadCredentialsException("Invalid bearer identifier", exception);
        }
    }
}
