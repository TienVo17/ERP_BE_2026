package com.company.erp.identity.security;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.company.erp.identity.application.PasswordService;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class AuthorizationMatrixIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SALE_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MANAGE_USERS_ID = UUID.fromString("30000000-0000-0000-0000-000000000043");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private com.nimbusds.jose.jwk.RSAKey testRsaKey;

    private UUID adminId;
    private UUID adminSessionId;
    private UUID saleId;
    private UUID saleSessionId;

    @BeforeEach
    void createPrincipals() {
        adminId = UUID.randomUUID();
        adminSessionId = createUserWithSession(adminId, "matrix-admin-" + adminId, "ACTIVE");
        assignRole(adminId, ADMIN_ROLE_ID);

        saleId = UUID.randomUUID();
        saleSessionId = createUserWithSession(saleId, "matrix-sale-" + saleId, "ACTIVE");
        assignRole(saleId, SALE_ROLE_ID);
    }

    @Test
    void anonymousAndSaleCannotCallAdminButAdminCan() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/admin/users").with(accessJwt(saleId, saleSessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/users").with(accessJwt(adminId, adminSessionId)))
                .andExpect(status().isOk());
    }

    @Test
    void explicitAllowGrantsAndExplicitDenyOverridesRoleGrant() throws Exception {
        jdbc.sql("""
                        INSERT INTO identity.user_permission_override (
                            user_id, permission_id, effect, reason, updated_by
                        ) VALUES (:userId, :permissionId, 'ALLOW', 'matrix allow', :actorId)
                        """)
                .param("userId", saleId)
                .param("permissionId", MANAGE_USERS_ID)
                .param("actorId", adminId)
                .update();

        mockMvc.perform(get("/api/v1/admin/users").with(accessJwt(saleId, saleSessionId)))
                .andExpect(status().isOk());

        int inserted = jdbc.sql("""
                        INSERT INTO identity.user_permission_override (
                            user_id, permission_id, effect, reason, updated_by
                        ) VALUES (:userId, :permissionId, 'DENY', 'matrix deny', :actorId)
                        ON CONFLICT (user_id, permission_id) DO NOTHING
                        """)
                .param("userId", adminId)
                .param("permissionId", MANAGE_USERS_ID)
                .param("actorId", adminId)
                .update();
        org.assertj.core.api.Assertions.assertThat(inserted).isEqualTo(1);

        mockMvc.perform(get("/api/v1/admin/users").with(accessJwt(adminId, adminSessionId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void forgedJwtClaimsCannotGrantAdminPermission() throws Exception {
        var user = identityRepository.findById(saleId).orElseThrow();
        var issued = jwtTokenService.issueSessionAccessToken(user, saleSessionId).token();
        var signed = com.nimbusds.jwt.SignedJWT.parse(issued);
        var original = signed.getJWTClaimsSet();
        var forged = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                        .keyID(testRsaKey.getKeyID())
                        .build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder(original)
                        .claim("roles", java.util.List.of("ADMIN"))
                        .claim("permissions", java.util.List.of("ADMIN:MANAGE_USERS"))
                        .build());
        forged.sign(new com.nimbusds.jose.crypto.RSASSASigner(testRsaKey));

        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged.serialize()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void disabledUserAndRevokedSessionAreRejectedAtNextRequest() throws Exception {
        jdbc.sql("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = :id")
                .param("id", adminId)
                .update();
        mockMvc.perform(get("/api/v1/admin/users").with(accessJwt(adminId, adminSessionId)))
                .andExpect(status().isUnauthorized());

        jdbc.sql("UPDATE identity.app_user SET status = 'ACTIVE' WHERE id = :id")
                .param("id", adminId)
                .update();
        jdbc.sql("UPDATE identity.auth_session SET revoked_at = clock_timestamp(), revoked_reason = 'TEST' WHERE id = :id")
                .param("id", adminSessionId)
                .update();
        mockMvc.perform(get("/api/v1/admin/users").with(accessJwt(adminId, adminSessionId)))
                .andExpect(status().isUnauthorized());
    }

    private UUID createUserWithSession(UUID userId, String loginId, String status) {
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status,
                            must_change_password, created_by, updated_by
                        ) VALUES (
                            :id, 'USER', :loginId, :passwordHash, 'QA', :name, :status,
                            false, :systemId, :systemId
                        )
                        """)
                .param("id", userId)
                .param("loginId", loginId)
                .param("passwordHash", passwordService.encode("a valid matrix password"))
                .param("name", loginId)
                .param("status", status)
                .param("systemId", SYSTEM_USER_ID)
                .update();
        UUID sessionId = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at,
                            created_at, last_refreshed_at, client_ip, user_agent
                        ) VALUES (
                            :id, :userId, :absoluteExpiresAt, :idleExpiresAt,
                            :now, :now, CAST('198.51.100.10' AS inet), 'authorization-matrix'
                        )
                        """)
                .param("id", sessionId)
                .param("userId", userId)
                .param("absoluteExpiresAt", now.plus(8, ChronoUnit.HOURS))
                .param("idleExpiresAt", now.plus(1, ChronoUnit.HOURS))
                .param("now", now)
                .update();
        return sessionId;
    }

    private void assignRole(UUID userId, UUID roleId) {
        jdbc.sql("INSERT INTO identity.user_role (user_id, role_id, assigned_by) VALUES (:userId, :roleId, :actorId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor accessJwt(
            UUID userId,
            UUID sessionId) {
        var user = identityRepository.findById(userId).orElseThrow();
        var token = jwtTokenService.issueSessionAccessToken(user, sessionId).token();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
