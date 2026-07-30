package com.company.erp.identity.api;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.company.erp.identity.application.PasswordService;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.identity.security.JwtTokenService;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class AdminUserControllerIT {

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ADMIN_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID SALE_ROLE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private JwtTokenService jwtTokenService;

    private UUID actorId;
    private UUID actorSessionId;

    @BeforeEach
    void createActorAndRecoveryAdmins() {
        actorId = createUser("admin-user-actor", "ACTIVE", false);
        assignRole(actorId, ADMIN_ROLE_ID);
        actorSessionId = createSession(actorId);
        UUID recoveryTwo = createUser("admin-user-recovery", "ACTIVE", false);
        assignRole(recoveryTwo, ADMIN_ROLE_ID);
    }

    @Test
    void createsUserWithCanonicalLoginAndHashedTemporaryPasswordWithoutCredentialLeak() throws Exception {
        String loginId = "  New.Admin." + UUID.randomUUID() + "  ";
        String temporaryPassword = "temporary admin password";

        MvcResult created = mockMvc.perform(post("/api/v1/admin/users")
                        .with(accessJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "kind", "USER",
                                "loginId", loginId,
                                "temporaryPassword", temporaryPassword,
                                "position", "QA",
                                "name", "New Admin"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.loginId").value(loginId.trim()))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.roleIds").isEmpty())
                .andExpect(jsonPath("$.permissionOverrides").isEmpty())
                .andExpect(content().string(not(containsString(temporaryPassword))))
                .andExpect(content().string(not(containsString("passwordHash"))))
                .andReturn();

        UUID createdId = UUID.fromString(json(created).get("id").asText());
        String hash = jdbc.sql("SELECT password_hash FROM identity.app_user WHERE id = :id")
                .param("id", createdId)
                .query(String.class)
                .single();
        assertThat(hash).isNotEqualTo(temporaryPassword);
        assertThat(passwordService.matches(temporaryPassword, hash)).isTrue();

        mockMvc.perform(post("/api/v1/admin/users")
                        .with(accessJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "kind", "USER",
                                "loginId", loginId.toLowerCase(),
                                "temporaryPassword", "another temporary password",
                                "position", "QA",
                                "name", "Duplicate"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BUSINESS_KEY"));
    }

    @Test
    void excludesSystemAndAppliesAllowlistedFiltersAndSort() throws Exception {
        String actorLoginId = jdbc.sql("SELECT login_id FROM identity.app_user WHERE id = :id")
                .param("id", actorId)
                .query(String.class)
                .single();
        mockMvc.perform(get("/api/v1/admin/users")
                        .with(accessJwt())
                        .param("status", "ACTIVE")
                        .param("kind", "USER")
                        .param("loginId", actorLoginId)
                        .param("sort", "loginId,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(actorId.toString()))
                .andExpect(jsonPath("$.filters.status").value("ACTIVE"))
                .andExpect(jsonPath("$.sort[0]").value("loginId,desc"))
                .andExpect(content().string(not(containsString(SYSTEM_USER_ID.toString()))));

        mockMvc.perform(get("/api/v1/admin/users")
                        .with(accessJwt())
                        .param("sort", "password_hash,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void staleProfileUpdateReturnsVersionConflictAndLoginIdCannotBeOverposted() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/{userId}", actorId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":"Lead","name":"Updated Admin"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(put("/api/v1/admin/users/{userId}", actorId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":"Lead","name":"Updated Admin","loginId":"forged"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void disableAndResetRevokeSessionsAndAuditWithoutSecrets() throws Exception {
        UUID targetId = createUser("admin-user-target", "ACTIVE", false);
        UUID targetSession = createSession(targetId);

        mockMvc.perform(put("/api/v1/admin/users/{userId}/status", targetId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED","reason":"employment ended"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(sessionRevoked(targetSession)).isTrue();

        UUID resetId = createUser("admin-user-reset", "ACTIVE", false);
        UUID resetSession = createSession(resetId);
        String temporaryPassword = "reset temporary password";
        mockMvc.perform(post("/api/v1/admin/users/{userId}/reset-password", resetId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "temporaryPassword", temporaryPassword,
                                "reason", "credential recovery"))))
                .andExpect(status().isNoContent());

        assertThat(sessionRevoked(resetSession)).isTrue();
        var credential = jdbc.sql("SELECT password_hash, must_change_password FROM identity.app_user WHERE id = :id")
                .param("id", resetId)
                .query((rs, rowNum) -> java.util.Map.of(
                        "hash", rs.getString("password_hash"),
                        "forced", rs.getBoolean("must_change_password")))
                .single();
        assertThat(credential.get("forced")).isEqualTo(true);
        assertThat(passwordService.matches(temporaryPassword, credential.get("hash").toString())).isTrue();

        String audit = jdbc.sql("""
                        SELECT coalesce(before_data::text, '') || coalesce(after_data::text, '')
                        FROM audit.audit_event
                        WHERE entity_id = :entityId
                        ORDER BY occurred_at DESC
                        LIMIT 1
                        """)
                .param("entityId", resetId)
                .query(String.class)
                .single();
        assertThat(audit)
                .doesNotContain(temporaryPassword)
                .doesNotContain("password_hash")
                .doesNotContain("passwordHash")
                .doesNotContain("token");
    }

    @Test
    void destructiveCommandCannotReduceRecoveryAdminsBelowTwo() throws Exception {
        java.util.List<UUID> recoveryIds = jdbc.sql("""
                        SELECT u.id
                        FROM identity.app_user u
                        JOIN identity.user_role ur ON ur.user_id = u.id
                        WHERE ur.role_id = :adminRoleId
                          AND u.status = 'ACTIVE'
                          AND u.must_change_password = false
                        ORDER BY u.id
                        """)
                .param("adminRoleId", ADMIN_ROLE_ID)
                .query(UUID.class)
                .list();
        assertThat(recoveryIds.size()).isGreaterThanOrEqualTo(2);
        for (int index = 0; index < recoveryIds.size() - 1; index++) {
            UUID id = recoveryIds.get(index);
            jdbc.sql("UPDATE identity.app_user SET status = 'DISABLED' WHERE id = :id")
                    .param("id", id)
                    .update();
        }
        UUID lastRecoveryId = recoveryIds.get(recoveryIds.size() - 1);
        actorId = lastRecoveryId;
        actorSessionId = createSession(actorId);
        long version = jdbc.sql("SELECT version FROM identity.app_user WHERE id = :id")
                .param("id", lastRecoveryId)
                .query(Long.class)
                .single();

        mockMvc.perform(put("/api/v1/admin/users/{userId}/status", lastRecoveryId)
                        .with(accessJwt())
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED","reason":"would remove recovery"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECOVERY_ADMIN_REQUIRED"));
    }

    private UUID createUser(String loginId, String status, boolean mustChangePassword) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status,
                            must_change_password, created_by, updated_by
                        ) VALUES (
                            :id, 'USER', :loginId, :passwordHash, 'QA', :loginId, :status,
                            :mustChangePassword, :systemId, :systemId
                        )
                        """)
                .param("id", id)
                .param("loginId", loginId + "-" + id)
                .param("passwordHash", passwordService.encode("an existing valid password"))
                .param("status", status)
                .param("mustChangePassword", mustChangePassword)
                .param("systemId", SYSTEM_USER_ID)
                .update();
        return id;
    }

    private void assignRole(UUID userId, UUID roleId) {
        jdbc.sql("INSERT INTO identity.user_role (user_id, role_id, assigned_by) VALUES (:userId, :roleId, :actorId)")
                .param("userId", userId)
                .param("roleId", roleId)
                .param("actorId", SYSTEM_USER_ID)
                .update();
    }

    private UUID createSession(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO identity.auth_session (
                            id, user_id, absolute_expires_at, idle_expires_at,
                            created_at, last_refreshed_at, client_ip, user_agent
                        ) VALUES (
                            :id, :userId, :absoluteExpiresAt, :idleExpiresAt,
                            :now, :now, CAST('198.51.100.20' AS inet), 'admin-user-test'
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

    private boolean sessionRevoked(UUID sessionId) {
        return jdbc.sql("SELECT revoked_at IS NOT NULL FROM identity.auth_session WHERE id = :id")
                .param("id", sessionId)
                .query(Boolean.class)
                .single();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor accessJwt() {
        var user = identityRepository.findById(actorId).orElseThrow();
        String token = jwtTokenService.issueSessionAccessToken(user, actorSessionId).token();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
